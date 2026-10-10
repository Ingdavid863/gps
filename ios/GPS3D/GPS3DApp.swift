import SwiftUI
import CarPlay

@main @MainActor final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,configurationForConnecting session: UISceneSession,options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let car=session.role == .carTemplateApplication
        let configuration=UISceneConfiguration(name:car ? "CarPlay":"Phone",sessionRole:session.role)
        configuration.delegateClass=car ? CarPlaySceneDelegate.self:PhoneSceneDelegate.self
        return configuration
    }
}
@MainActor final class PhoneSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    func scene(_ scene: UIScene,willConnectTo session: UISceneSession,options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene=scene as? UIWindowScene else { return }
        let window=UIWindow(windowScene:scene)
        window.rootViewController=UIHostingController(rootView:NavigationView())
        self.window=window;window.makeKeyAndVisible()
    }
}
private struct LiveMap: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> MapController { MapController() }
    func updateUIViewController(_ controller: MapController,context: Context) {}
}
struct NavigationView: View {
    @ObservedObject private var store=NavigationStore.shared
    @FocusState private var searching: Bool
    @State private var credits=false
    var body: some View {
        ZStack {
            LiveMap().ignoresSafeArea()
            VStack(spacing:12) {
                HStack {
                    Button { credits=true } label: { Image(systemName:"line.3.horizontal") }.accessibilityLabel("Menú")
                    TextField("¿A dónde vas?",text:$store.query).focused($searching).submitLabel(.search)
                        .onSubmit { store.search(store.query) }
                        .onChange(of:store.query) { store.search($0) }
                    Button { store.clearSearch();searching=true } label: { Image(systemName:"xmark") }.accessibilityLabel("Limpiar destino")
                }.padding().background(.regularMaterial,in:RoundedRectangle(cornerRadius:22))
                if !store.results.isEmpty {
                    VStack(alignment:.leading,spacing:0) {
                        ForEach(store.results) { place in
                            Button { store.selected=place;searching=false } label: {
                                VStack(alignment:.leading) { Text(place.name).bold();Text(place.address).font(.caption) }
                                    .frame(maxWidth:.infinity,alignment:.leading).padding(12)
                            }
                        }
                    }.background(.regularMaterial,in:RoundedRectangle(cornerRadius:18))
                }
                if store.route != nil {
                    VStack(alignment:.leading,spacing:6) {
                        Text(store.pending ? "Recalculando ruta…" : (store.arrived ? "Llegaste a tu destino":store.instruction)).font(.title3.bold())
                        if !store.arrived { Text("En \(Int(store.turnMeters)) m").font(.largeTitle.bold()) }
                    }.frame(maxWidth:.infinity,alignment:.leading).padding().background(.regularMaterial,in:RoundedRectangle(cornerRadius:22))
                }
                Spacer()
                if let place=store.selected {
                    VStack(alignment:.leading,spacing:10) {
                        Text(place.name).font(.title3.bold());Text(place.address).font(.caption)
                        HStack { Button("Cerrar") { store.selected=nil };Spacer();Button("Ir") { searching=false;store.routeTo(place) }.buttonStyle(.borderedProminent) }
                    }.padding().background(.regularMaterial,in:RoundedRectangle(cornerRadius:22))
                }
                if let route=store.route {
                    HStack {
                        VStack(alignment:.leading) { Text("\(Int(ceil(store.seconds/60))) min").font(.title.bold());Text(String(format:"%.1f km",store.remaining/1000)) }
                        Spacer()
                        Button(route.avoidsTolls ? "Permitir casetas":"Evitar caseta") { store.toggleTolls() }.disabled(store.pending)
                        Button { store.stop() } label: { Image(systemName:"xmark.circle.fill").foregroundStyle(.red).font(.title) }.accessibilityLabel("Terminar ruta")
                    }.padding().background(.regularMaterial,in:RoundedRectangle(cornerRadius:22))
                }
            }.padding()
        }.onAppear { store.startLocation() }
            .alert("GPS3D",isPresented:Binding(get:{store.error != nil},set:{if !$0 { store.dismissError() }})) { Button("Entendido") { store.dismissError() } } message: { Text(store.error ?? "") }
            .sheet(isPresented:$credits) {
                VStack(alignment:.leading,spacing:20) {
                    Text("Créditos del mapa").font(.title.bold())
                    Text("© TomTom · © OpenStreetMap contributors. MapLibre · OpenFreeMap · EOX Sentinel-2.")
                    Link("OpenStreetMap y su licencia",destination:URL(string:"https://www.openstreetmap.org/copyright")!)
                    Link("TomTom",destination:URL(string:"https://www.tomtom.com/legal/")!)
                    Toggle("Indicaciones por voz",isOn:Binding(get:{store.voiceEnabled},set:{store.voiceEnabled=$0}))
                    Button("Cerrar") { credits=false }
                }.padding().presentationDetents([.medium])
            }
    }
}
