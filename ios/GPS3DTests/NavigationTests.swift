import XCTest
import CoreLocation
@testable import GPS3D

private final class StubProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> Data)?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let data=try Self.handler!(request)
            client?.urlProtocol(self,didReceive:HTTPURLResponse(url:request.url!,statusCode:200,httpVersion:nil,headerFields:nil)!,cacheStoragePolicy:.notAllowed)
            client?.urlProtocol(self,didLoad:data);client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self,didFailWithError:error) }
    }
    override func stopLoading() {}
}
@MainActor final class NavigationTests: XCTestCase {
    private func point(_ east: Double,_ north: Double) -> GeoPoint {
        .init(lat:19.72+north/110540,lon:-99.22+east/(111320*cos(19.72 * .pi/180)))
    }
    private func fix(_ point: GeoPoint,_ seconds: Double=0) -> CLLocation {
        CLLocation(coordinate:point.coordinate,altitude:0,horizontalAccuracy:3,verticalAccuracy:5,course:0,speed:0,timestamp:Date().addingTimeInterval(seconds))
    }
    private func client() -> TomTomClient {
        let config=URLSessionConfiguration.ephemeral;config.protocolClasses=[StubProtocol.self]
        return TomTomClient(key:"fixture",session:URLSession(configuration:config))
    }
    private func routeJSON(_ points: [GeoPoint], paid: Bool=false) -> [String:Any] {
        ["summary":["lengthInMeters":200.0,"travelTimeInSeconds":120.0],
         "legs":[["points":points.map { ["latitude":$0.lat,"longitude":$0.lon] }]],
         "sections":paid ? [["sectionType":"TOLL"]]:[],
         "guidance":["instructions":[["point":["latitude":points.last!.lat,"longitude":points.last!.lon],"pointIndex":points.count-1,"message":"Llegaste","maneuver":"ARRIVE"]]]]
    }
    func testMetricProgressTrimsTheApproachAfterTheTurnAndClampsAtArrival() {
        let points=[point(0,0),point(0,100),point(100,100)]
        let geometry=RouteGeometry(points)
        let match=geometry.match(point(35,100),heading:0,previous:100,speed:5)!
        XCTAssertEqual(match.segment,1)
        let remaining=geometry.remaining(after:match.along)
        XCTAssertEqual(remaining.count,2);XCTAssertGreaterThan(remaining[0].lon,points[1].lon)
        XCTAssertEqual(geometry.instructionAlong(points[1],index:1,offset:85),geometry.cumulative[1],accuracy:0.1)
        XCTAssertTrue(geometry.remaining(after:geometry.length).isEmpty)
    }
    func testTollBoothsDoNotMatchAParallelStreetOrTheOpposingLane() {
        let booth=TollBooth(id:1,lat:point(0,100).lat,lon:point(0,100).lon,bearings:[0])
        XCTAssertEqual(RouteGeometry([point(0,0),point(0,200)]).crossedBooths([booth]).count,1)
        XCTAssertTrue(RouteGeometry([point(35,0),point(35,200)]).crossedBooths([booth]).isEmpty)
        XCTAssertTrue(RouteGeometry([point(0,200),point(0,0)]).crossedBooths([booth]).isEmpty)
    }
    func testAvoidTollsUsesCurrentOriginAndRejectsPaidAlternativesAutomatically() async throws {
        let paid=[point(0,0),point(0,200)], free=[point(0,0),point(100,0),point(100,200),point(0,200)]
        let store=NavigationStore(client:client(),booths:[]);store.voiceEnabled=false;store.fix(fix(paid[0]))
        var avoid=false, count=0
        StubProtocol.handler={ request in
            count+=1
            let query=URLComponents(url:request.url!,resolvingAgainstBaseURL:false)!.queryItems!
            XCTAssertEqual(query.first { $0.name=="travelMode" }?.value,"car")
            XCTAssertEqual(query.first { $0.name=="traffic" }?.value,"true")
            if avoid { XCTAssertEqual(query.first { $0.name=="avoid" }?.value,"tollRoads") }
            return try JSONSerialization.data(withJSONObject:["routes":avoid ? [self.routeJSON(paid,paid:true),self.routeJSON(free)]:[self.routeJSON(paid,paid:true)]])
        }
        let destination=Place(name:"Destino",address:"",point:paid.last!)
        await store.routeTo(destination)!.value
        XCTAssertEqual(store.route!.points,paid);XCTAssertFalse(store.route!.avoidsTolls)
        avoid=true;let task=store.routeTo(destination,avoid:true)!
        XCTAssertEqual(store.route!.points,paid);XCTAssertFalse(store.route!.avoidsTolls);XCTAssertTrue(store.pending)
        await task.value
        XCTAssertEqual(store.route!.points,free);XCTAssertTrue(store.route!.avoidsTolls);XCTAssertEqual(count,2)
        store.fix(fix(point(0,200),1));XCTAssertLessThan(store.remaining,1)
    }
    func testMissingTollMetadataTriggersAvoidAreasAndFailureKeepsTheOldRoute() async throws {
        let paid=[point(0,0),point(0,200)]
        let booth=TollBooth(id:1,lat:point(0,100).lat,lon:point(0,100).lon,bearings:[0])
        let store=NavigationStore(client:client(),booths:[booth]);store.voiceEnabled=false;store.fix(fix(paid[0]))
        var count=0
        StubProtocol.handler={ request in
            count+=1
            if count==3 { XCTAssertEqual(request.httpMethod,"POST");XCTAssertTrue(request.httpBody != nil || request.httpBodyStream != nil) }
            return try JSONSerialization.data(withJSONObject:["routes":[self.routeJSON(paid)]])
        }
        let destination=Place(name:"Destino",address:"",point:paid.last!)
        await store.routeTo(destination)!.value
        let routeID=store.route!.id
        await store.routeTo(destination,avoid:true)!.value
        XCTAssertEqual(count,3);XCTAssertEqual(store.route!.id,routeID);XCTAssertFalse(store.route!.avoidsTolls)
        XCTAssertNotNil(store.error);XCTAssertFalse(store.pending)
    }
    func testClearSearchCancelsOldResultsAndKeepsTheLowerGoActionAvailable() async {
        let store=NavigationStore(client:client(),booths:[]);store.voiceEnabled=false
        store.query="Jardín de Eventos del Sol";store.selected=Place(name:store.query,address:"",point:point(0,0))
        let task=store.search(store.query);store.clearSearch();await task.value
        XCTAssertEqual(store.query,"");XCTAssertNil(store.selected);XCTAssertTrue(store.results.isEmpty)
        store.selected=Place(name:"Otro destino",address:"",point:point(100,100))
        XCTAssertEqual(store.selected?.name,"Otro destino")
    }
}
