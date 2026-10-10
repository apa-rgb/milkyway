import XCTest
@testable import MilkywayCore

final class OperationsTests: XCTestCase {
    let now = ISO8601DateFormatter().date(from: "2026-10-10T12:00:00Z")!
    func order(_ doc: inout SharedDocument, line: String = "POWDER") throws -> Row {
        try Operations.addOrder(&doc, id: "order", line: line, title: "Mleko", description: "Opis", quantity: "100", now: now)
        return doc.row("production_queue", "order")!
    }
    func scheduled(_ doc: inout SharedDocument) throws -> Row {
        let row = try order(&doc); try Operations.schedule(&doc, expected: row, day: "2026-10-10", time: "10:00", now: now, codeID: "automatic-production-code:a")
        return doc.row("production_queue", "order")!
    }
    func testAutomaticCodeAndManualOverride() throws {
        var doc = try SharedDocument(domain: .production); var row = try scheduled(&doc)
        XCTAssertEqual(text(Operations.codeNotes(doc, entryID: "order").first!, "note"), "Kod produkcji: 283")
        doc.put("production_product_notes", id: "manual", row: ["id": "manual", "entry_id": "order", "stage": "PRODUCTION", "note": "Kod produkcji: 123", "created_at": Operations.timestamp(now) + 1])
        try Operations.schedule(&doc, expected: row, day: "2026-10-11", time: "12:00", now: now.addingTimeInterval(1), codeID: "automatic-production-code:b")
        row = doc.row("production_queue", "order")!
        XCTAssertEqual(text(row, "plan_date"), "2026-10-11"); XCTAssertEqual(text(Operations.codeNotes(doc, entryID: "order").first!, "note"), "Kod produkcji: 123")
    }
    func testCodeOnlyInPowder() throws {
        for line in ["BUTTER", "UHT"] { var doc = try SharedDocument(domain: .production); let row = try order(&doc, line: line); try Operations.schedule(&doc, expected: row, day: "2026-10-10", time: "10:00", now: now, codeID: "automatic-production-code:a"); XCTAssertTrue(doc.rows("production_product_notes").isEmpty) }
    }
    func testPartialProductionRetryAndExcess() throws {
        var doc = try SharedDocument(domain: .production); let row = try scheduled(&doc)
        try Operations.complete(&doc, expected: row, receiptID: "a", quantity: "30", now: now)
        try Operations.complete(&doc, expected: row, receiptID: "a", quantity: "30", now: now)
        XCTAssertEqual(Operations.remaining(doc.row("production_queue", "order")!), 70); XCTAssertEqual(doc.rows("production_completions").count, 1)
        try Operations.complete(&doc, expected: doc.row("production_queue", "order")!, receiptID: "b", quantity: "80", now: now)
        XCTAssertEqual(number(doc.row("production_queue", "order")!, "produced_amount"), 110); XCTAssertFalse(Operations.active(doc.row("production_queue", "order")!))
    }
    func testOvernightCompletionBelongsToPreviousProductionDay() throws {
        var doc = try SharedDocument(domain: .production); let row = try scheduled(&doc)
        let nextMorning = ISO8601DateFormatter().date(from: "2026-10-11T06:59:00Z")!
        try Operations.complete(&doc, expected: row, receiptID: "receipt", quantity: nil, now: nextMorning)
        XCTAssertEqual(text(doc.row("production_completions", "receipt")!, "produced_on"), "2026-10-10")
    }
    func testStaleScheduleAndNoteRejected() throws {
        var doc = try SharedDocument(domain: .production); let row = try scheduled(&doc)
        try Operations.returnPending(&doc, expected: row, now: now.addingTimeInterval(1))
        XCTAssertThrowsError(try Operations.schedule(&doc, expected: row, day: "2026-10-11", time: "10:00", now: now, codeID: "automatic-production-code:b"))
    }
    func testConcurrentChangeWithinSameMillisecondIsRejected() throws {
        var doc = try SharedDocument(domain: .production); let row = try scheduled(&doc)
        try Operations.returnPending(&doc, expected: row, now: now)
        XCTAssertThrowsError(try Operations.schedule(&doc, expected: row, day: "2026-10-11", time: "10:00", now: now, codeID: "automatic-production-code:b"))
    }
    func testWarehouseRemovalPreservesLedgerAndRejectsChangedReceiptSet() throws {
        var doc = try SharedDocument(domain: .production); let row = try scheduled(&doc)
        try Operations.complete(&doc, expected: row, receiptID: "a", quantity: "30", now: now)
        try Operations.complete(&doc, expected: doc.row("production_queue", "order")!, receiptID: "b", quantity: "20", now: now)
        XCTAssertThrowsError(try Operations.removeWarehouse(&doc, entryID: "order", day: "2026-10-10", receiptIDs: ["a"], requestID: "remove", pin: "5522", now: now))
        XCTAssertThrowsError(try Operations.removeWarehouse(&doc, entryID: "order", day: "2026-10-10", receiptIDs: ["a", "b"], requestID: "remove", pin: "1111", now: now))
        try Operations.removeWarehouse(&doc, entryID: "order", day: "2026-10-10", receiptIDs: ["a", "b"], requestID: "remove", pin: "5522", now: now)
        try Operations.removeWarehouse(&doc, entryID: "order", day: "2026-10-10", receiptIDs: ["a", "b"], requestID: "remove", pin: "5522", now: now)
        XCTAssertEqual(doc.rows("production_completions").count, 2); XCTAssertEqual(number(doc.row("production_queue", "order")!, "produced_amount"), 50)
        XCTAssertNotNil(doc.row("production_completions", "a")!["warehouse_removed_at"])
    }
    let tank = Tank(id: "Olej 12", name: "Olej 12", description: "Olej", capacityLitres: 10000, group: "Oleje")
    func testCubicMetresAndBatchRemainCompatibleWithAndroid() throws {
        var doc = try SharedDocument(domain: .inventory); let empty: Row = ["tank_id": tank.id, "material": "", "oil_type": ""]
        let batch = OilBatch(expires: "2026-10-12", notes: "Nowa partia")
        try Operations.editTank(&doc, tank: tank, expected: empty, fields: ["oil_type": "Rzepakowy", "temperature": "20"], cubicMetres: "1,25", batch: batch, laboratory: false, role: "operator", requestID: "edit", now: now)
        XCTAssertEqual(number(doc.row("tank_states", tank.id)!, "litres"), 1250); XCTAssertEqual(Operations.oilBatch(doc, tankID: tank.id), batch)
        XCTAssertEqual(doc.rows("movements").count, 2); XCTAssertNotNil(doc.row("tank_states", tank.id)!["filled_at"])
    }
    func testTankCapacityAndLabRoleChecked() throws {
        var doc = try SharedDocument(domain: .inventory); let empty: Row = ["tank_id": tank.id, "material": "", "oil_type": ""]
        XCTAssertThrowsError(try Operations.editTank(&doc, tank: tank, expected: empty, fields: [:], cubicMetres: "11", batch: nil, laboratory: false, role: "operator", requestID: "a", now: now))
        doc = try SharedDocument(domain: .inventory)
        XCTAssertThrowsError(try Operations.editTank(&doc, tank: tank, expected: empty, fields: ["ph": "7"], cubicMetres: "1", batch: nil, laboratory: true, role: "operator", requestID: "b", now: now))
    }
    func testTopUpsAccumulateAndRetryDoesNotDuplicate() throws {
        var doc = try SharedDocument(domain: .inventory); doc.put("tank_states", id: tank.id, row: ["tank_id": tank.id, "litres": "0", "material": "", "oil_type": ""])
        for id in ["a", "b", "b"] { try Operations.addMaterial(&doc, target: tank, source: nil, cubicMetres: "0.5", external: "Cysterna", note: "Dostawa", requestID: id, now: now) }
        XCTAssertEqual(number(doc.row("tank_states", tank.id)!, "litres"), 1000); XCTAssertEqual(doc.rows("movements").count, 2)
    }
    func testTransferConservesMaterialAndClearsEmptyBatch() throws {
        var doc = try SharedDocument(domain: .inventory); let source = Tank(id: "Olej 13", name: "Olej 13", description: "Olej", capacityLitres: 20000, group: "Oleje")
        doc.put("tank_states", id: source.id, row: ["tank_id": source.id, "litres": "1000", "material": "", "oil_type": "", "filled_at": 100])
        doc.put("tank_states", id: tank.id, row: ["tank_id": tank.id, "litres": "0", "material": "", "oil_type": ""])
        try Operations.addMaterial(&doc, target: tank, source: source, cubicMetres: "1", external: "", note: "Transfer", requestID: "a", now: now)
        XCTAssertEqual(number(doc.row("tank_states", source.id)!, "litres"), 0); XCTAssertEqual(number(doc.row("tank_states", tank.id)!, "litres"), 1000)
        XCTAssertNil(doc.row("tank_states", source.id)!["filled_at"])
    }
    func testNotesSaveAndReminderReadPreserveOtherAccounts() throws {
        var doc = try SharedDocument(domain: .notes); var note = Annotation("Sprawdź zbiorniki"); note.author = "Anna"; note.reminder = Reminder(at: Operations.timestamp(now) + 1000)
        try Operations.saveNote(&doc, expected: nil, id: "note", scope: 0, kind: "CURRENT_NOTES", annotation: note, now: now)
        try Operations.reminderAction(&doc, table: "work_notes", id: "note", token: note.reminder!.token, action: "read", account: "01", now: now)
        try Operations.reminderAction(&doc, table: "work_notes", id: "note", token: note.reminder!.token, action: "read", account: "02", now: now)
        let saved = Annotation(text(doc.row("work_notes", "note")!, "body"))
        XCTAssertEqual(saved.reminder!.readers, ["01", "02"]); XCTAssertEqual(saved.author, "Anna"); XCTAssertEqual(text(doc.row("work_notes", "note")!, "title"), "")
    }
}
