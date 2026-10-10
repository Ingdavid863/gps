const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createTracker,distance,bearingDelta}=require('../app/src/main/assets/navigation-motion.js');
const {create}=require('../app/src/main/assets/navigation-route-progress.js');
const path=create([[0,0],[0,100/110540],[100/111320,100/110540]]);
test('one-second GPS cadence stays in motion between fixes with less than two metres of rendering delay',()=>{
    const tracker=createTracker();
    for(let tick=0;tick<4;tick++) {
        const at=tick*1000;
        tracker.fix({now:at,point:path.at(tick*20),bearing:0,speed:20,path,along:tick*20});
        let previous=tracker.sample(at).along;
        for(let offset=20;offset<=980;offset+=20) {
            const pose=tracker.sample(at+offset);
            assert.ok(pose.along>previous,'vehicle must not stop while waiting for the next fix');
            assert.ok(Math.abs(pose.along-(tick*20+offset*.02))<2,'no added GPS-cycle delay');
            previous=pose.along;
        }
    }
});
test('coarse heading fixes animate on every frame, retain angular velocity and cross north by the shortest path',()=>{
    const tracker=createTracker();
    tracker.fix({now:0,point:[0,0],bearing:359,speed:0});
    tracker.fix({now:1000,point:[0,0],bearing:14,speed:0});
    let previous=359;
    for(let now=1016;now<=1260;now+=16) {
        const pose=tracker.sample(now),delta=bearingDelta(previous,pose.bearing);
        assert.ok(delta>0&&delta<2,'15 degree inputs must be spread across frames');
        previous=pose.bearing;
    }
    tracker.fix({now:1260,point:[0,0],bearing:29,speed:0});
    assert.ok(bearingDelta(previous,tracker.sample(1276).bearing)>0,'new fixes retain motion');
});
test('predicted motion follows the bend, clamps at arrival and stops when GPS fixes become stale',()=>{
    const tracker=createTracker();
    tracker.fix({now:0,point:path.at(95),bearing:0,speed:10,path,along:95});
    for(let now=20;now<1500;now+=20) {
        const pose=tracker.sample(now);
        assert.ok(distance(pose.point,path.at(pose.along))<.01,'never cut through a corner');
    }
    let late;
    for(let now=1500;now<=5000;now+=20)late=tracker.sample(now);
    const stopped=tracker.sample(5500);
    assert.ok(distance(late.point,stopped.point)<.01,'stale fixes must not keep advancing');
    assert.ok(stopped.along<=107.01,'prediction is limited to the fresh-fix horizon');
    tracker.fix({now:6000,point:path.at(199),bearing:90,speed:20,path,along:199});
    for(let now=6020;now<=7500;now+=20)late=tracker.sample(now);
    assert.ok(distance(late.point,path.at(path.total))<.01,'arrival clamps to the destination');
    assert.deepEqual(path.remaining(path.total),[]);
});
test('remaining route starts at exact progress even on long segments and after repeated leg endpoints',()=>{
    const repeated=create([[0,0],[0,0],[0,100/110540],[100/111320,100/110540]]);
    const along=repeated.project(1,[0,60/110540]);
    assert.ok(Math.abs(along-60)<.001);
    const remaining=repeated.remaining(along);
    assert.ok(distance(remaining[0],[0,60/110540])<.001);
    assert.equal(remaining.length,3);
    assert.equal(repeated.indexAt(100),2);
    const after=repeated.remaining(110);
    assert.equal(after.length,2);
    assert.ok(after[0][0]>0);
    assert.equal(after[0][1],100/110540);
});
