const assert = require('node:assert/strict');
const { padding, overviewPoints } = require('../app/src/main/assets/navigation-view.js');
for (const [width, height, top, bottom] of [[390, 840, .25, .16], [360, 720, .28, .19], [430, 932, .22, .15]]) {
    const p = padding(width, height, top, bottom, false);
    const anchorY = (height + p.top - p.bottom) / 2;
    assert.ok(Math.abs(anchorY - (height * (1 - bottom) - 48)) < 0.01);
    assert.ok(anchorY > height * top);
    assert.ok(p.top + p.bottom < height);
    const all = padding(width, height, top, bottom, true);
    assert.ok(all.top >= height * top);
    assert.ok(all.bottom >= height * bottom);
}
const route = [[-99.2, 19.4], [-99.1, 19.5], [-99.0, 19.6]];
assert.deepEqual(overviewPoints(route, [-98, 18], null), route);
assert.equal(overviewPoints([], [-99, 19], null).length, 1);
assert.equal(overviewPoints([], [-99, 19], [-99.4, 19.8]).length, 2);
console.log('Follow anchor and complete-route bounds passed.');
