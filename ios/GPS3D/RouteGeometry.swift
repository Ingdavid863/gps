import Foundation
import CoreLocation

struct GeoPoint: Equatable, Codable {
    let lat: Double
    let lon: Double
    var coordinate: CLLocationCoordinate2D { .init(latitude: lat, longitude: lon) }
    var json: [Double] { [lon, lat] }
}
struct RouteStep {
    let point: GeoPoint
    let message: String
    let maneuver: String
    let along: Double
    let index: Int
}
struct RoutePlan {
    let id = UUID()
    let points: [GeoPoint]
    let steps: [RouteStep]
    let meters: Double
    let seconds: Double
    let hasTolls: Bool
    let avoidsTolls: Bool
}
struct Place: Identifiable, Equatable {
    var id: String { "\(point.lat),\(point.lon):\(name)" }
    let name: String
    let address: String
    let point: GeoPoint
}
struct TollBooth: Decodable {
    let id: Int
    let lat: Double
    let lon: Double
    let bearings: [Double]
    var point: GeoPoint { .init(lat: lat, lon: lon) }
}
struct TollCatalog: Decodable {
    let plazas: [TollBooth]
    static func bundled() -> [TollBooth] {
        guard let url = Bundle.main.url(forResource: "mx-tolls", withExtension: "json", subdirectory: "MapAssets"),
              let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode(Self.self, from: data).plazas) ?? []
    }
}
struct RouteGeometry {
    struct Match { let segment: Int; let point: GeoPoint; let distance: Double; let along: Double; let bearing: Double }
    let points: [GeoPoint]
    let cumulative: [Double]
    var length: Double { cumulative.last ?? 0 }
    init(_ points: [GeoPoint]) {
        self.points = points
        var distances = [0.0]
        if points.count > 1 {
            for i in 1..<points.count { distances.append(distances.last! + Self.distance(points[i-1], points[i])) }
        }
        cumulative = distances
    }
    static func distance(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        CLLocation(latitude: a.lat, longitude: a.lon).distance(from: CLLocation(latitude: b.lat, longitude: b.lon))
    }
    static func angle(_ a: Double, _ b: Double) -> Double {
        abs((a-b+540).truncatingRemainder(dividingBy: 360)-180)
    }
    func project(_ p: GeoPoint, onto i: Int) -> Match? {
        guard i >= 0, i < points.count-1 else { return nil }
        let a=points[i], b=points[i+1], sx=111320*cos(p.lat * .pi/180), sy=110540.0
        let dx=(b.lon-a.lon)*sx, dy=(b.lat-a.lat)*sy, px=(p.lon-a.lon)*sx, py=(p.lat-a.lat)*sy
        guard hypot(dx,dy) > 0.01 else { return nil }
        let t=max(0,min(1,(px*dx+py*dy)/(dx*dx+dy*dy)))
        return Match(segment:i, point:.init(lat:a.lat+(b.lat-a.lat)*t,lon:a.lon+(b.lon-a.lon)*t),
            distance:hypot(px-t*dx,py-t*dy), along:cumulative[i]+t*(cumulative[i+1]-cumulative[i]),
            bearing:(atan2(dx,dy)*180 / .pi+360).truncatingRemainder(dividingBy:360))
    }
    func match(_ point: GeoPoint, heading: Double?=nil, previous: Double?=nil, speed: Double=0, accuracy: Double=10) -> Match? {
        var best: Match?, score=Double.infinity
        guard points.count > 1 else { return nil }
        for i in 0..<points.count-1 {
            if let previous=previous, cumulative[i+1]<previous-min(50,max(25,accuracy*2)) || cumulative[i]>previous+min(400,max(100,speed*5)) { continue }
            guard let p=project(point,onto:i) else { continue }
            let delta=heading.map { Self.angle(p.bearing,$0) } ?? 0
            let jump=previous.map { max(0,p.along-$0-speed-max(20,accuracy*2))*0.35 } ?? 0
            let s=p.distance+max(0,delta-105)*0.65+jump
            if s<score { best=p;score=s }
        }
        return best
    }
    func instructionAlong(_ point: GeoPoint, index: Int, offset: Double?) -> Double {
        if points.count > 1, index >= 0, index < points.count {
            let near=(max(0,index-1)...min(points.count-2,index+1)).compactMap { project(point,onto:$0) }.min { $0.distance<$1.distance }
            if let near=near, near.distance<20 { return near.along }
        }
        if let offset=offset, offset.isFinite, offset>=0, offset<=length+5 { return min(length,offset) }
        return match(point)?.along ?? 0
    }
    func crossedBooths(_ booths: [TollBooth]) -> [TollBooth] {
        guard !points.isEmpty else { return [] }
        let minLat=points.map(\.lat).min()!-0.0002, maxLat=points.map(\.lat).max()!+0.0002
        let minLon=points.map(\.lon).min()!-0.0002, maxLon=points.map(\.lon).max()!+0.0002
        return booths.filter { booth in
            guard booth.lat>=minLat, booth.lat<=maxLat, booth.lon>=minLon, booth.lon<=maxLon,
                  let p=match(booth.point), p.distance<=12 else { return false }
            return booth.bearings.isEmpty || booth.bearings.contains { Self.angle($0,p.bearing)<70 }
        }
    }
    func remaining(after along: Double) -> [GeoPoint] {
        guard points.count>1, along<length-0.5 else { return [] }
        let i=(0..<points.count-1).first { cumulative[$0+1]>=along } ?? points.count-2
        let t=max(0,min(1,(along-cumulative[i])/max(0.001,cumulative[i+1]-cumulative[i])))
        return [GeoPoint(lat:points[i].lat+(points[i+1].lat-points[i].lat)*t,lon:points[i].lon+(points[i+1].lon-points[i].lon)*t)] + Array(points[(i+1)...])
    }
}
