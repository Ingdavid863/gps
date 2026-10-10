import Foundation

struct NavigationError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}
final class TomTomClient {
    let key: String
    let session: URLSession
    init(key: String = Bundle.main.object(forInfoDictionaryKey: "TOMTOM_API_KEY") as? String ?? "", session: URLSession = .shared) {
        self.key=key;self.session=session
    }
    private func json(_ request: URLRequest) async throws -> [String:Any] {
        guard !key.isEmpty, !key.contains("$(") else { throw NavigationError(message:"El servicio de rutas no está configurado") }
        let (data,response)=try await session.data(for:request)
        guard let status=response as? HTTPURLResponse, (200..<300).contains(status.statusCode) else {
            throw NavigationError(message:"No se pudo consultar el servicio de mapas")
        }
        return try JSONSerialization.jsonObject(with:data) as? [String:Any] ?? [:]
    }
    func search(_ query: String, near: GeoPoint?) async throws -> [Place] {
        guard !query.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else { return [] }
        var url=URLComponents(string:"https://api.tomtom.com/search/2/search/" + query.addingPercentEncoding(withAllowedCharacters:.urlPathAllowed)! + ".json")!
        url.queryItems=[.init(name:"key",value:key),.init(name:"language",value:"es-MX"),.init(name:"limit",value:"6"),.init(name:"typeahead",value:"true")]
        if let near=near { url.queryItems! += [.init(name:"lat",value:String(near.lat)),.init(name:"lon",value:String(near.lon))] }
        let response=try await json(URLRequest(url:url.url!))
        return (response["results"] as? [[String:Any]] ?? []).compactMap { item in
            guard let p=item["position"] as? [String:Double], let lat=p["lat"], let lon=p["lon"] else { return nil }
            let address=(item["address"] as? [String:Any])?["freeformAddress"] as? String ?? ""
            let title=(item["poi"] as? [String:Any])?["name"] as? String ?? address
            return Place(name:title,address:address,point:.init(lat:lat,lon:lon))
        }
    }
    func routes(from: GeoPoint, to: GeoPoint, avoid: Bool, booths: [TollBooth], exclusions: [TollBooth]=[]) async throws -> [RoutePlan] {
        var url=URLComponents(string:"https://api.tomtom.com/routing/1/calculateRoute/\(from.lat),\(from.lon):\(to.lat),\(to.lon)/json")!
        url.queryItems=[.init(name:"key",value:key),.init(name:"traffic",value:"true"),.init(name:"routeType",value:"fastest"),
            .init(name:"travelMode",value:"car"),.init(name:"instructionsType",value:"text"),.init(name:"language",value:"es-ES"),
            .init(name:"sectionType",value:"toll"),.init(name:"sectionType",value:"tollVignette"),.init(name:"maxAlternatives",value:"2")]
        if avoid { url.queryItems!.append(.init(name:"avoid",value:"tollRoads")) }
        var request=URLRequest(url:url.url!);request.timeoutInterval=30
        if !exclusions.isEmpty {
            let rectangles=exclusions.prefix(100).map { booth -> [String:Any] in
                let dy=20.0/111320, dx=dy/cos(booth.lat * .pi/180)
                return ["southWestCorner":["latitude":booth.lat-dy,"longitude":booth.lon-dx],
                        "northEastCorner":["latitude":booth.lat+dy,"longitude":booth.lon+dx]]
            }
            request.httpMethod="POST";request.setValue("application/json",forHTTPHeaderField:"Content-Type")
            request.httpBody=try JSONSerialization.data(withJSONObject:["avoidAreas":["rectangles":rectangles]])
        }
        let response=try await json(request)
        return (response["routes"] as? [[String:Any]] ?? []).compactMap { Self.parse($0,avoid:avoid,booths:booths) }
    }
    static func parse(_ json: [String:Any], avoid: Bool, booths: [TollBooth]) -> RoutePlan? {
        let legs=json["legs"] as? [[String:Any]] ?? []
        let points=legs.flatMap { ($0["points"] as? [[String:Any]] ?? []).compactMap { p -> GeoPoint? in
            guard let lat=p["latitude"] as? Double, let lon=p["longitude"] as? Double else { return nil }
            return GeoPoint(lat:lat,lon:lon)
        } }
        guard points.count>1, let summary=json["summary"] as? [String:Any],
              let meters=summary["lengthInMeters"] as? Double, let seconds=summary["travelTimeInSeconds"] as? Double else { return nil }
        let geometry=RouteGeometry(points)
        let sections=json["sections"] as? [[String:Any]] ?? []
        let hasTolls=sections.contains { ["TOLL","TOLL_ROAD","TOLL_VIGNETTE"].contains($0["sectionType"] as? String ?? "") } || !geometry.crossedBooths(booths).isEmpty
        let instructions=(json["guidance"] as? [String:Any])?["instructions"] as? [[String:Any]] ?? []
        let steps=instructions.compactMap { item -> RouteStep? in
            guard let p=item["point"] as? [String:Any],let lat=p["latitude"] as? Double, let lon=p["longitude"] as? Double else { return nil }
            let point=GeoPoint(lat:lat,lon:lon)
            return RouteStep(point:point,message:item["message"] as? String ?? "Continúa hacia tu destino",
                maneuver:item["maneuver"] as? String ?? "STRAIGHT",along:geometry.instructionAlong(point,index:item["pointIndex"] as? Int ?? -1,offset:item["routeOffsetInMeters"] as? Double),index:max(0,min(points.count-1,item["pointIndex"] as? Int ?? 0)))
        }
        return RoutePlan(points:points,steps:steps,meters:meters,seconds:seconds,hasTolls:hasTolls,avoidsTolls:avoid)
    }
}
