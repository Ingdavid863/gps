import CarPlay
import MapKit
import Combine

@MainActor final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate, CPSearchTemplateDelegate, CPMapTemplateDelegate {
    private let store=NavigationStore.shared
    private var interface: CPInterfaceController?
    private var map=CPMapTemplate()
    private var navigation: CPNavigationSession?
    private var currentTrip: CPTrip?
    private var revision: UUID?
    private var observation: AnyCancellable?
    private var searchEpoch=0
    private var searchTask: Task<Void,Never>?
    private var lastError: String?
    func templateApplicationScene(_ scene: CPTemplateApplicationScene,didConnect controller: CPInterfaceController,to window: CPWindow) {
        interface=controller;window.rootViewController=MapController(car:true);map.mapDelegate=self
        controller.setRootTemplate(map,animated:false,completion:nil)
        store.startLocation()
        observation=store.objectWillChange.receive(on:DispatchQueue.main).sink { [weak self] _ in self?.update() }
        update()
    }
    func templateApplicationScene(_ scene: CPTemplateApplicationScene,didDisconnect controller: CPInterfaceController,from window: CPWindow) {
        observation=nil;searchTask?.cancel();navigation?.cancelTrip();navigation=nil;currentTrip=nil;revision=nil;interface=nil
        window.rootViewController=nil
    }
    private func makeTrip(_ destination: Place) -> CPTrip {
        let point=store.position ?? destination.point
        let origin=MKMapItem(placemark:MKPlacemark(coordinate:point.coordinate));origin.name="Tu ubicación"
        let target=MKMapItem(placemark:MKPlacemark(coordinate:destination.point.coordinate));target.name=destination.name
        let active=store.destination?.id == destination.id
        let choice=CPRouteChoice(summaryVariants:[active ? "\(Int(ceil(store.seconds/60))) min":"Calcular ruta"],additionalInformationVariants:[active ? String(format:"%.1f km",store.remaining/1000):destination.address],selectionSummaryVariants:[destination.name])
        return CPTrip(origin:origin,destination:target,routeChoices:[choice])
    }
    private func update() {
        let search=CPBarButton(type:.image) { [weak self] _ in
            guard let self=self else { return };let template=CPSearchTemplate();template.delegate=self
            self.interface?.pushTemplate(template,animated:true,completion:nil)
        };search.image=UIImage(systemName:"magnifyingglass")
        let toll=CPBarButton(type:.text) { [weak self] _ in self?.store.toggleTolls() }
        toll.title=store.route?.avoidsTolls == true ? "Permitir casetas":"Evitar caseta";toll.isEnabled=store.route != nil && !store.pending
        map.leadingNavigationBarButtons=[search,toll]
        let stop=CPBarButton(type:.text) { [weak self] _ in self?.store.stop() };stop.title="Terminar";stop.isEnabled=store.route != nil
        map.trailingNavigationBarButtons=[stop]
        if store.arrived { navigation?.finishTrip();navigation=nil;currentTrip=nil;return }
        if let route=store.route,let destination=store.destination {
            if revision != route.id { navigation?.cancelTrip();navigation=nil;currentTrip=nil;revision=route.id }
            if navigation==nil { let trip=makeTrip(destination);currentTrip=trip;navigation=map.startNavigationSession(for:trip) }
            let maneuver=CPManeuver();maneuver.instructionVariants=[store.pending ? "Recalculando ruta…":store.instruction]
            let symbol=store.maneuver.contains("LEFT") ? "arrow.turn.up.left" : (store.maneuver.contains("RIGHT") ? "arrow.turn.up.right" : (store.maneuver.contains("ARRIVE") ? "flag.checkered":"arrow.up"))
            maneuver.symbolImage=UIImage(systemName:symbol)
            maneuver.initialTravelEstimates=CPTravelEstimates(distanceRemaining:Measurement(value:store.turnMeters,unit:UnitLength.meters),timeRemaining:store.seconds*store.turnMeters/max(1,store.remaining))
            navigation?.upcomingManeuvers=[maneuver]
            navigation?.updateEstimates(maneuver.initialTravelEstimates!,for:maneuver)
            if let trip=currentTrip { map.update(CPTravelEstimates(distanceRemaining:Measurement(value:store.remaining,unit:UnitLength.meters),timeRemaining:store.seconds),for:trip) }
        } else if navigation != nil { navigation?.cancelTrip();navigation=nil;currentTrip=nil;revision=nil }
        if let error=store.error,error != lastError {
            lastError=error
            let alert=CPAlertTemplate(titleVariants:[error],actions:[CPAlertAction(title:"Entendido",style:.default) { [weak self] _ in
                self?.store.dismissError();self?.interface?.dismissTemplate(animated:true,completion:nil)
            }])
            interface?.presentTemplate(alert,animated:true,completion:nil)
        } else if store.error==nil { lastError=nil }
    }
    func searchTemplate(_ searchTemplate: CPSearchTemplate,updatedSearchText searchText: String,completionHandler: @escaping ([CPListItem])->Void) {
        searchEpoch+=1;let id=searchEpoch;searchTask?.cancel()
        searchTask=Task { [weak self] in
            guard let self=self else { completionHandler([]);return }
            do {
                let places=try await self.store.client.search(searchText,near:self.store.position)
                guard !Task.isCancelled,id==self.searchEpoch else { completionHandler([]);return }
                completionHandler(places.map { place in
                    let row=CPListItem(text:place.name,detailText:place.address);row.userInfo=place;return row
                })
            } catch { completionHandler([]) }
        }
    }
    func searchTemplate(_ searchTemplate: CPSearchTemplate,selectedResult item: CPListItem,completionHandler: @escaping ()->Void) {
        guard let place=item.userInfo as? Place else { completionHandler();return }
        store.selected=place
        let trip=makeTrip(place)
        map.showTripPreviews([trip],textConfiguration:CPTripPreviewTextConfiguration(startButtonTitle:"Ir",additionalRoutesButtonTitle:nil,overviewButtonTitle:nil))
        interface?.popToRootTemplate(animated:true,completion:nil);completionHandler()
    }
    func mapTemplate(_ mapTemplate: CPMapTemplate,startedTrip trip: CPTrip,using routeChoice: CPRouteChoice) {
        guard let place=store.selected else { return };mapTemplate.hideTripPreviews();store.routeTo(place)
    }
    func mapTemplateDidCancelNavigation(_ mapTemplate: CPMapTemplate) { store.stop() }
}
