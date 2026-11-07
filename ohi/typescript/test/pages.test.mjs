import assert from "node:assert/strict";
import { test } from "node:test";
import { ohiReleasePage } from "../dist/index.js";

test("links a release page on ohi.dev", () => {
  assert.equal(ohiReleasePage({ namespace: "w3c", name: "sosa", version: "2017" }), "https://ohi.dev/o/w3c/sosa@2017");
});

test("encodes each part of the release page path", () => {
  assert.equal(
    ohiReleasePage({ namespace: "a b", name: "x/y@z", version: "1.0+amendment.1 é" }),
    "https://ohi.dev/o/a%20b/x%2Fy%40z@1.0%2Bamendment.1%20%C3%A9",
  );
});
