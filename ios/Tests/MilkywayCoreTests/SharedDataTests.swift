import XCTest
@testable import MilkywayCore

final class SharedDataTests: XCTestCase {
    func time(_ value: String) -> Date { ISO8601DateFormatter().date(from: value)! }
    func testPolishDecimalAndPrecision() throws {
        XCTAssertEqual(try amount("12,375"), Decimal(string: "12.375"))
        for v in ["12abc", "NaN", "1e3", "-2", "0", "1.0001"] { XCTAssertThrowsError(try amount(v), v) }
        XCTAssertEqual(try amount("0", positive: false), 0)
    }
    func testSharedKeyMatchesAndroid() { XCTAssertEqual(sharedKey("Olej 12"), "T2xlaiAxMg"); XCTAssertFalse(sharedKey("ą/🔥").contains("=")) }
    func testUnknownColumnsAndActorSurviveEditing() throws {
        var doc = try SharedDocument(domain: .notes)
        doc.put("work_notes", id: "x", row: ["id": "x", "future_field": "kept", "_actor": ["uid": "old"]])
        doc.put("work_notes", id: "y", row: ["id": "y", "body": "old"]); let old = doc
        var row = doc.row("work_notes", "y")!; row["body"] = "new"; doc.put("work_notes", id: "y", row: row)
        doc.commit(actor: ["uid": "new", "account": "01"], at: 123, previous: old)
        XCTAssertEqual(text(doc.row("work_notes", "x")!, "future_field"), "kept")
        XCTAssertEqual(text(doc.row("work_notes", "x")!["_actor"] as! Row, "uid"), "old")
        XCTAssertEqual(text(doc.row("work_notes", "y")!["_actor"] as! Row, "uid"), "new")
        XCTAssertEqual(integer(doc.root, "revision"), 1)
    }
    func testUnsupportedSchemaRejectedAndProductionV1Accepted() throws {
        XCTAssertThrowsError(try SharedDocument(["schema": 99], domain: .notes))
        XCTAssertThrowsError(try SharedDocument(["schema": 1, "tables": ["secret": [:]]], domain: .notes))
        XCTAssertNoThrow(try SharedDocument(["schema": 1], domain: .production))
    }
    func testWarehouseBoundaryUsesWarsawNotDeviceUTC() {
        XCTAssertEqual(ProductionClock.warehouseDay(time("2026-10-10T06:59:59Z")), "2026-10-09")
        XCTAssertEqual(ProductionClock.warehouseDay(time("2026-10-10T07:00:00Z")), "2026-10-10")
        XCTAssertTrue(ProductionClock.canComplete("2026-10-09", now: time("2026-10-10T06:59:59Z")))
        XCTAssertFalse(ProductionClock.canComplete("2026-10-09", now: time("2026-10-10T07:00:00Z")))
    }
    func testDSTAndYearBoundary() {
        XCTAssertEqual(ProductionClock.warehouseDay(time("2026-10-25T07:59:59Z")), "2026-10-24")
        XCTAssertEqual(ProductionClock.warehouseDay(time("2026-10-25T08:00:00Z")), "2026-10-25")
        XCTAssertEqual(ProductionClock.warehouseDay(time("2027-01-01T07:00:00Z")), "2026-12-31")
    }
    func testDayOfYearCode() { XCTAssertEqual(ProductionClock.code("2026-10-10"), "283"); XCTAssertEqual(ProductionClock.code("2028-12-31"), "366"); XCTAssertEqual(ProductionClock.code("2026-01-01"), "001") }
    func testNoteMarkersMatchAndroidAndRoundTrip() {
        let body = "[Ważne]\n[Autor: Anna]\n[Przypomnienie: 1791637200000|00000000-0000-0000-0000-000000000000|01,10|0]\n[Załatwione]\nTreść"
        let note = Annotation(body)
        XCTAssertEqual(note.author, "Anna"); XCTAssertTrue(note.important); XCTAssertTrue(note.completed)
        XCTAssertEqual(note.reminder?.readers, ["01", "10"]); XCTAssertEqual(note.body, "Treść"); XCTAssertEqual(note.encoded, body)
    }
    func testMalformedMarkerRemainsVisible() { let s = "[Przypomnienie: błędny]\nTekst"; XCTAssertEqual(Annotation(s).body, s); XCTAssertNil(Annotation(s).reminder) }
    func testReminderReadIsPerAccount() {
        let r = Reminder(at: 100, readers: ["01"])
        XCTAssertFalse(r.due(now: 101, account: "01")); XCTAssertTrue(r.due(now: 101, account: "02")); XCTAssertFalse(r.due(now: 99, account: "02"))
    }
    func testOilAuditAndNoonWarning() {
        let b = OilBatch(produced: "2026-10-01", expires: "2026-10-11", notes: "Partia żółta\nUwagi")
        XCTAssertEqual(OilBatch(audit: "Poprzedni rodzaj oleju: rzepakowy" + b.suffix), b)
        XCTAssertEqual(b.urgency(litres: 10, now: time("2026-10-10T10:00:00Z")), 1)
        XCTAssertEqual(b.urgency(litres: 10, now: time("2026-10-10T10:00:01Z")), 2)
        XCTAssertEqual(b.urgency(litres: 0, now: time("2026-10-12T10:00:00Z")), 0)
    }
}
