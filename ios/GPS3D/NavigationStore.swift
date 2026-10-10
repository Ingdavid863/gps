import Foundation
import Combine
import CoreLocation
import AVFoundation

@MainActor final class NavigationStore: NSObject, ObservableObject, CLLocationManagerDelegate, AVSpeechSynthesizerDelegate {
    static let shared=NavigationStore()
    @Published var query=""
    @Published private(set) var results=[Place]()
    @Published var selected: Place?
    @Published private(set) var route: RoutePlan?
    @Published private(set) var pending=false
    @Published private(set) var error: String?
    @Published private(set) var position: GeoPoint?
    @Published private(set) var bearing=0.0
    @Published private(set) var speed=0.0
    @Published private(set) var along=0.0
    @Published private(set) var segment=0
    @Published private(set) var remaining=0.0
    @Published private(set) var seconds=0.0
    @Published private(set) var instruction="Busca un destino para comenzar"
    @Published private(set) var maneuver="STRAIGHT"
    @Published private(set) var turnMeters=0.0
    @Published private(set) var onRoute=false
    @Published private(set) var arrived=false
    private(set) var destination: Place?
    let client: TomTomClient
    private let manager=CLLocationManager()
    private let speech=AVSpeechSynthesizer()
    private let booths: [TollBooth]
    private var raw: CLLocation?
    private var geometry: RouteGeometry?
    private var routeTask: Task<Void,Never>?
    private var searchTask: Task<Void,Never>?
    private var routeEpoch=0, searchEpoch=0
    private var lastSpoken=""
    private var offRouteSince: Date?, lastReroute=Date.distantPast
    var voiceEnabled=true
    init(client: TomTomClient=TomTomClient(), booths: [TollBooth]=TollCatalog.bundled()) {
        self.client=client;self.booths=booths;super.init()
        manager.delegate=self;manager.desiredAccuracy=kCLLocationAccuracyBestForNavigation
        manager.distanceFilter=kCLDistanceFilterNone;manager.activityType = .automotiveNavigation
        manager.pausesLocationUpdatesAutomatically=false
        speech.delegate=self
    }
    func startLocation() {
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        manager.startUpdatingLocation()
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse { manager.startUpdatingLocation() }
        else if manager.authorizationStatus == .denied { error="Activa la ubicación para comenzar la ruta" }
    }
    func locationManager(_ manager: CLLocationManager,didUpdateLocations locations: [CLLocation]) {
        locations.forEach { fix($0) }
    }
    func fix(_ location: CLLocation, force: Bool=false) {
        guard location.horizontalAccuracy>=0, force || raw == nil || location.timestamp>raw!.timestamp else { return }
        raw=location;speed=max(0,location.speed)
        if location.course>=0, speed>0.8 { bearing=location.course }
        let point=GeoPoint(lat:location.coordinate.latitude,lon:location.coordinate.longitude)
        guard let geometry=geometry,let route=route else { position=point;return }
        let match=geometry.match(point,heading:speed>1 ? bearing:nil,previous:along>0 ? along:nil,speed:speed,accuracy:location.horizontalAccuracy)
        onRoute=(match?.distance ?? .infinity)<25
        if onRoute,let match=match { along=max(along,match.along);segment=match.segment;position=match.point;offRouteSince=nil }
        else { position=point;if offRouteSince==nil { offRouteSince=Date() } }
        remaining=max(0,geometry.length-along);seconds=route.seconds*remaining/max(1,geometry.length)
        let step=route.steps.first { !$0.maneuver.hasPrefix("DEPART") && $0.along>=along-3 } ?? route.steps.last
        instruction=step?.message ?? "Continúa hacia tu destino";maneuver=step?.maneuver ?? "STRAIGHT";turnMeters=max(0,(step?.along ?? geometry.length)-along)
        arrived=onRoute && remaining<5
        if voiceEnabled && onRoute && !pending {
            let phase=turnMeters<35 ? "ahora" : (turnMeters<200 ? "próxima" : "lejos")
            let token="\(route.id):\(instruction):\(phase)"
            if token != lastSpoken {
                lastSpoken=token
                let cue=phase=="ahora" ? instruction : "En \(Int(turnMeters)) metros, \(instruction)"
                try? AVAudioSession.sharedInstance().setCategory(.playback,mode:.voicePrompt,options:[.duckOthers,.interruptSpokenAudioAndMixWithOthers])
                try? AVAudioSession.sharedInstance().setActive(true)
                let utterance=AVSpeechUtterance(string:cue);utterance.voice=AVSpeechSynthesisVoice(language:"es-MX")
                speech.stopSpeaking(at:.immediate);speech.speak(utterance)
            }
        }
        if !onRoute, !pending, let since=offRouteSince,Date().timeIntervalSince(since)>2.4,Date().timeIntervalSince(lastReroute)>6.5,let destination=destination {
            lastReroute=Date();routeTo(destination,avoid:route.avoidsTolls)
        }
    }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,didFinish utterance: AVSpeechUtterance) {
        try? AVAudioSession.sharedInstance().setActive(false,options:.notifyOthersOnDeactivation)
    }
    func dismissError() { error=nil }
    func clearSearch() {
        searchEpoch+=1;searchTask?.cancel();searchTask=nil;query="";results=[];selected=nil
    }
    @discardableResult func search(_ text: String) -> Task<Void,Never> {
        searchEpoch+=1;let id=searchEpoch;searchTask?.cancel()
        if text.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty { results=[];selected=nil }
        let task=Task { [weak self] in
            guard let self=self else { return }
            do {
                try await Task.sleep(nanoseconds:240_000_000)
                let places=try await self.client.search(text,near:self.position)
                guard !Task.isCancelled,id==self.searchEpoch else { return }
                self.results=places
            } catch { if !Task.isCancelled,id==self.searchEpoch { self.results=[] } }
        }
        searchTask=task;return task
    }
    @discardableResult func routeTo(_ place: Place, avoid: Bool?=nil) -> Task<Void,Never>? {
        guard let raw=raw else { error="Esperando ubicación GPS…";return nil }
        let origin=GeoPoint(lat:raw.coordinate.latitude,lon:raw.coordinate.longitude)
        let avoiding=avoid ?? route?.avoidsTolls ?? false
        routeEpoch+=1;let id=routeEpoch;routeTask?.cancel();pending=true;error=nil
        let task=Task { [weak self] in
            guard let self=self else { return }
            do {
                var options=try await self.client.routes(from:origin,to:place.point,avoid:avoiding,booths:self.booths)
                guard !Task.isCancelled,id==self.routeEpoch else { return }
                var accepted=options.first { !avoiding || !$0.hasTolls }
                if accepted==nil, avoiding {
                    let exclusions=options.flatMap { RouteGeometry($0.points).crossedBooths(self.booths) }
                    if !exclusions.isEmpty {
                        options=try await self.client.routes(from:origin,to:place.point,avoid:true,booths:self.booths,exclusions:exclusions)
                        accepted=options.first { !$0.hasTolls }
                    }
                }
                guard !Task.isCancelled,id==self.routeEpoch else { return }
                guard let accepted=accepted else { throw NavigationError(message:avoiding ? "No se encontró una ruta disponible sin casetas" : "No se encontró una ruta disponible") }
                self.route=accepted;self.destination=place;self.selected=nil;self.results=[]
                self.geometry=RouteGeometry(accepted.points);self.along=0;self.segment=0;self.pending=false;self.arrived=false
                if let latest=self.raw { self.fix(latest,force:true) }
                if self.manager.authorizationStatus == .authorizedAlways || self.manager.authorizationStatus == .authorizedWhenInUse {
                    self.manager.allowsBackgroundLocationUpdates=true
                }
            } catch {
                if !Task.isCancelled,id==self.routeEpoch { self.pending=false;self.error=error.localizedDescription }
            }
        }
        routeTask=task;return task
    }
    func toggleTolls() {
        guard !pending,let destination=destination,let route=route else { return }
        routeTo(destination,avoid:!route.avoidsTolls)
    }
    func stop() {
        routeEpoch+=1;routeTask?.cancel();routeTask=nil;route=nil;geometry=nil;destination=nil;pending=false;along=0
        remaining=0;seconds=0;arrived=false;onRoute=false;instruction="Busca un destino para comenzar"
        manager.allowsBackgroundLocationUpdates=false;speech.stopSpeaking(at:.immediate)
    }
}
