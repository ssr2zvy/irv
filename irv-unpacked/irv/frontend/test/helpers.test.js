import {test}from'node:test';import assert from'node:assert/strict';import{remaining,parse}from'../src/api.js';
test('countdown never becomes negative',()=>{assert.equal(remaining(1000,2000),'0:00');assert.equal(remaining(181000,1000),'3:00')});
test('invalid stored tree has safe fallback',()=>{assert.deepEqual(parse('bad',[]),[])});
