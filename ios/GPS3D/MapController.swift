import UIKit
import WebKit
import Combine

private final class WeakMapHandler: NSObject, WKScriptMessageHandler {
    weak var owner: MapController?
    init(_ owner: MapController) { self.owner=owner }
    func userContentController(_ userContentController: WKUserContentController,didReceive message: WKScriptMessage) { owner?.receive(message) }
}
@MainActor final class MapController: UIViewController {
    let store: NavigationStore
    let car: Bool
    private(set) var web: WKWebView!
    private var observation: AnyCancellable?
    private var ready=false
    private var renderedID: UUID?
    init(store: NavigationStore = .shared, car: Bool=false) { self.store=store;self.car=car;super.init(nibName:nil,bundle:nil) }
    required init?(coder: NSCoder) { fatalError("Use init(store:car:)") }
    override func viewDidLoad() {
        super.viewDidLoad()
        let config=WKWebViewConfiguration()
        config.userContentController.add(WeakMapHandler(self),name:"navigation")
        let key=Self.json(store.client.key)
        let bridge="""
        window.GPS3D_CONFIG={tomtomApiKey:\(key)};
        window.AndroidBridge={
          onMapReady:()=>webkit.messageHandlers.navigation.postMessage({type:'ready'}),
          onMapError:e=>webkit.messageHandlers.navigation.postMessage({type:'error',value:String(e)}),
          onTrafficSeverity:()=>{}, prepareMapViewport:()=>{},
          previewDestination:(lat,lon)=>webkit.messageHandlers.navigation.postMessage({type:'select',lat,lon})
        };
        """
        config.userContentController.addUserScript(WKUserScript(source:bridge,injectionTime:.atDocumentStart,forMainFrameOnly:true))
        web=WKWebView(frame:view.bounds,configuration:config);web.autoresizingMask=[.flexibleWidth,.flexibleHeight]
        web.isOpaque=false;web.backgroundColor = .systemBackground;view.addSubview(web)
        if let url=Bundle.main.url(forResource:"map3d",withExtension:"html",subdirectory:"MapAssets") {
            web.loadFileURL(url,allowingReadAccessTo:url.deletingLastPathComponent())
        }
        observation=store.objectWillChange.receive(on:DispatchQueue.main).sink { [weak self] _ in self?.render() }
    }
    func receive(_ message: WKScriptMessage) {
        guard let item=message.body as? [String:Any] else { return }
        if item["type"] as? String == "ready" { ready=true;render() }
        if !car, item["type"] as? String == "select",let lat=item["lat"] as? Double,let lon=item["lon"] as? Double {
            store.selected=Place(name:"Punto del mapa",address:"",point:.init(lat:lat,lon:lon))
        }
    }
    override func viewSafeAreaInsetsDidChange() { super.viewSafeAreaInsetsDidChange();render() }
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) { super.traitCollectionDidChange(previousTraitCollection);render() }
    func render() {
        guard ready else { return }
        let dark=traitCollection.userInterfaceStyle == .dark
        let h=max(1,view.bounds.height)
        let top=car ? min(0.6,Double(view.safeAreaInsets.top/h)+0.04):0.16
        let bottom=car ? min(0.6,Double(view.safeAreaInsets.bottom/h)+0.06):0.24
        var calls=["setDarkTheme(\(dark))","setViewport(\(top),\(bottom))"]
        if renderedID != store.route?.id {
            renderedID=store.route?.id
            calls.append("setRoutes(\(Self.json(store.route?.points.map(\.json) ?? [])),[],[],false)")
            calls.append("setManeuvers(\(Self.json(store.route?.steps.map { step -> [String:Any] in ["index":step.index,"maneuver":step.maneuver] } ?? [])))")
        }
        if let point=store.position {
            calls.append("setLocation(\(point.lon),\(point.lat),\(store.bearing),\(store.speed),\(store.onRoute))")
            if store.route != nil,store.onRoute { calls.append("setRouteProgress(\(point.lon),\(point.lat),\(store.segment))") }
            calls.append("follow(\(point.lon),\(point.lat),\(store.bearing),17.4,0,1)")
        }
        if !car {
            if let selected=store.selected { calls.append("setSelectionPin(\(selected.point.lon),\(selected.point.lat))") }
            else { calls.append("clearSelectionPin()") }
        }
        web.evaluateJavaScript(calls.map { "window.GPS3D && GPS3D.\($0);" }.joined())
    }
    static func json(_ object: Any) -> String {
        guard let data=try? JSONSerialization.data(withJSONObject:object,options:[.fragmentsAllowed,.sortedKeys]),
              let string=String(data:data,encoding:.utf8) else { return "null" }
        return string
    }
}
