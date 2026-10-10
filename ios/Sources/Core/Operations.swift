import Foundation

public enum Operations {
    public static func timestamp(_ now: Date) -> Int64 { Int64(now.timeIntervalSince1970 * 1000) }
    public static func remaining(_ row: Row) -> Decimal? { number(row, "planned_amount").map { max(0, $0 - (number(row, "produced_amount") ?? 0)) } }
    public static func active(_ row: Row) -> Bool { integer(row, "pending_order") == 1 || remaining(row) != 0 }
    static func position(_ doc: SharedDocument, line: String, day: String) -> Int64 {
        (doc.rows("production_queue").filter { text($0, "line") == line && text($0, "plan_date") == day }.map { integer($0, "position") }.max() ?? -1) + 1
    }
    public static func current(_ doc: SharedDocument, table: String, expected: Row) throws -> Row {
        guard let row = doc.row(table, text(expected, "id")) else { throw MilkywayError.message("Ten wpis został usunięty.") }
        var current = row; var original = expected
        current.removeValue(forKey: "_actor"); original.removeValue(forKey: "_actor")
        try require(equalRows(current, original), "Wpis został zmieniony przez inną osobę. Otwórz go ponownie.")
        return row
    }
    public static func addOrder(_ doc: inout SharedDocument, id: String, line: String, title: String, description: String, quantity: String, now: Date) throws {
        if doc.row("production_queue", id) != nil { return }
        try require(["BUTTER", "POWDER", "UHT"].contains(line), "Wybierz dział.")
        try require(!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && title.count <= 120 && description.count <= 2000, "Wpisz nazwę produktu (do 120 znaków) i opis (do 2000).")
        let amount = try amount(quantity); let day = ProductionClock.day(now); let t = timestamp(now)
        doc.put("production_queue", id: id, row: ["id": id, "line": line, "plan_date": day,
            "position": position(doc, line: line, day: day), "title": title.trimmingCharacters(in: .whitespacesAndNewlines), "description": description,
            "planned_amount": string(amount), "produced_amount": "0", "unit": line == "UHT" ? "LITRES" : "KG",
            "pending_order": 1, "created_at": t, "updated_at": t])
    }
    public static func schedule(_ doc: inout SharedDocument, expected: Row, day: String, time: String, now: Date, codeID: String) throws {
        var row = try current(doc, table: "production_queue", expected: expected)
        try require(active(row), "Ten produkt jest już wyprodukowany.")
        try require(ProductionClock.date(day) != nil && ("1900-01-01"..."2100-12-31").contains(day), "Wybierz poprawną datę.")
        try require(time.range(of: "^([01][0-9]|2[0-3]):[0-5][0-9]$", options: .regularExpression) != nil, "Wybierz godzinę.")
        let wasPending = integer(row, "pending_order") == 1
        row["position"] = position(doc, line: text(row, "line"), day: day); row["plan_date"] = day
        row["planned_time"] = time; row["pending_order"] = 0; row["updated_at"] = timestamp(now)
        let id = text(row, "id"); doc.put("production_queue", id: id, row: row)
        if text(row, "line") == "POWDER" {
            let latest = codeNotes(doc, entryID: id).first
            if (latest == nil && wasPending) || (latest != nil && text(latest!, "id").hasPrefix("automatic-production-code:")) {
                let code = ProductionClock.code(day)
                if latest == nil || text(latest!, "note") != "Kod produkcji: \(code)" {
                    doc.put("production_product_notes", id: codeID, row: ["id": codeID, "entry_id": id, "stage": "PRODUCTION", "note": "Kod produkcji: \(code)", "created_at": timestamp(now)])
                }
            }
        }
    }
    public static func codeNotes(_ doc: SharedDocument, entryID: String) -> [Row] {
        doc.rows("production_product_notes").filter { text($0, "entry_id") == entryID && isCode(text($0, "note")) }.sorted {
            integer($0, "created_at") == integer($1, "created_at") ? integer($0, "_rowid") > integer($1, "_rowid") : integer($0, "created_at") > integer($1, "created_at")
        }
    }
    public static func isCode(_ note: String) -> Bool { note.range(of: "^Kod produkcji: ([0-9]{3}|—)$", options: .regularExpression) != nil }
    public static func returnPending(_ doc: inout SharedDocument, expected: Row, now: Date) throws {
        var row = try current(doc, table: "production_queue", expected: expected)
        try require(active(row), "Produkt jest już wyprodukowany.")
        row["pending_order"] = 1; row.removeValue(forKey: "planned_time"); row["updated_at"] = timestamp(now)
        row["position"] = position(doc, line: text(row, "line"), day: text(row, "plan_date"))
        doc.put("production_queue", id: text(row, "id"), row: row)
    }
    public static func move(_ doc: inout SharedDocument, expected: Row, direction: Int, now: Date) throws {
        var row = try current(doc, table: "production_queue", expected: expected)
        let peers = doc.rows("production_queue").filter { text($0, "line") == text(row, "line") && text($0, "plan_date") == text(row, "plan_date") && integer($0, "pending_order") == integer(row, "pending_order") && text($0, "planned_time") == text(row, "planned_time") && active($0) }.sorted { integer($0, "position") < integer($1, "position") }
        guard let index = peers.firstIndex(where: { text($0, "id") == text(row, "id") }), peers.indices.contains(index + direction) else { return }
        var other = peers[index + direction]; let old = row["position"]
        row["position"] = other["position"]; other["position"] = old; row["updated_at"] = timestamp(now); other["updated_at"] = timestamp(now)
        doc.put("production_queue", id: text(row, "id"), row: row); doc.put("production_queue", id: text(other, "id"), row: other)
    }
    public static func complete(_ doc: inout SharedDocument, expected: Row, receiptID: String, quantity: String?, now: Date) throws {
        if let receipt = doc.row("production_completions", receiptID) { try require(text(receipt, "entry_id") == text(expected, "id"), "Zapis należy do innego produktu."); return }
        var row = try current(doc, table: "production_queue", expected: expected)
        try require(integer(row, "pending_order") == 0 && ProductionClock.canComplete(text(row, "plan_date"), now: now), "Wykonanie można zapisać dla bieżącego dnia; kolejka poprzedniego dnia jest dostępna do 09:00.")
        guard let left = remaining(row), left > 0 else { throw MilkywayError.message("Uzupełnij ilość lub sprawdź, czy produkt jest już wykonany.") }
        let qty = try quantity.map { try amount($0) } ?? left
        row["produced_amount"] = string((number(row, "produced_amount") ?? 0) + qty); row["updated_at"] = timestamp(now)
        doc.put("production_queue", id: text(row, "id"), row: row)
        doc.put("production_completions", id: receiptID, row: ["id": receiptID, "entry_id": text(row, "id"), "amount": string(qty), "produced_on": ProductionClock.warehouseDay(now), "occurred_at": timestamp(now)])
    }
    public static func deleteOrder(_ doc: inout SharedDocument, expected: Row, pin: String) throws {
        try require(pin == "5522", "Nieprawidłowy PIN.")
        let row = try current(doc, table: "production_queue", expected: expected)
        try require((number(row, "produced_amount") ?? 0) == 0 && !doc.rows("production_completions").contains { text($0, "entry_id") == text(row, "id") }, "Produkt ma historię wykonania. Możesz usunąć jego wpis w magazynie.")
        doc.remove("production_queue", id: text(row, "id"))
        for note in doc.rows("production_product_notes") where text(note, "entry_id") == text(row, "id") { doc.remove("production_product_notes", id: text(note, "id")) }
    }
    public static func removeWarehouse(_ doc: inout SharedDocument, entryID: String, day: String, receiptIDs: Set<String>, requestID: String, pin: String, now: Date) throws {
        try require(pin == "5522", "Nieprawidłowy PIN.")
        let all = doc.rows("production_completions")
        let prior = all.filter { text($0, "warehouse_removal_id") == requestID }
        if !prior.isEmpty { try require(Set(prior.map { text($0, "id") }) == receiptIDs, "Nieprawidłowy identyfikator usunięcia."); return }
        let rows = all.filter { text($0, "entry_id") == entryID && text($0, "produced_on") == day && $0["warehouse_removed_at"] == nil }
        try require(!receiptIDs.isEmpty && Set(rows.map { text($0, "id") }) == receiptIDs, "Wpis magazynu zmienił się. Otwórz usuwanie ponownie.")
        for var row in rows { row["warehouse_removed_at"] = timestamp(now); row["warehouse_removal_id"] = requestID; doc.put("production_completions", id: text(row, "id"), row: row) }
    }
    public static func saveNote(_ doc: inout SharedDocument, expected: Row?, id: String, scope: Int, kind: String, annotation: Annotation, now: Date) throws {
        try require((0...3).contains(scope) && !annotation.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && annotation.body.count <= 10000, "Wpisz treść notatki (do 10 000 znaków).")
        var row: Row = [:]; var note = annotation
        if let expected {
            row = try current(doc, table: "work_notes", expected: expected)
            try require(integer(row, "scope") == scope && text(row, "kind") == kind, "Notatka należy do innej sekcji.")
            note.author = Annotation(text(row, "body")).author
        } else if doc.row("work_notes", id) != nil { return }
        if let r = note.reminder, r.token != Annotation(text(row, "body")).reminder?.token { try require(r.at > timestamp(now), "Wybierz przyszłą datę i godzinę przypomnienia.") }
        note.reminder?.done = note.completed
        row["id"] = id; row["scope"] = scope; row["kind"] = kind; row["title"] = ""; row["body"] = note.encoded
        row["completed"] = note.completed ? 1 : 0; row["created_at"] = row["created_at"] ?? timestamp(now); row["updated_at"] = timestamp(now)
        doc.put("work_notes", id: id, row: row)
    }
    public static func reminderAction(_ doc: inout SharedDocument, table: String, id: String, token: String, action: String, account: String, now: Date) throws {
        guard var row = doc.row(table, id) else { throw MilkywayError.message("Notatka została usunięta.") }
        let column = table == "work_notes" ? "body" : "note"; var note = Annotation(text(row, column))
        try require(note.reminder?.token == token, "Termin przypomnienia został zmieniony.")
        switch action {
        case "read": note.reminder?.readers.insert(account)
        case "done": note.reminder?.done = true; note.completed = true; if table == "work_notes" { row["completed"] = 1 }
        case "urgent": note.important = true
        default: throw MilkywayError.message("Nieprawidłowa czynność.")
        }
        row[column] = note.encoded; if table == "work_notes" { row["updated_at"] = timestamp(now) }; doc.put(table, id: id, row: row)
    }
    public static func oilBatch(_ doc: SharedDocument, tankID: String) -> OilBatch {
        var batch = OilBatch(); var known: Decimal?
        for event in doc.rows("movements") {
            let target = text(event, "target_id") == tankID; let source = text(event, "source_id") == tankID
            if target {
                let type = text(event, "type")
                if type == "OIL_TYPE" { batch = OilBatch(audit: text(event, "note")) ?? OilBatch(); if known == nil { known = number(event, "previous_litres") } }
                if type == "SET_STATE" { known = number(event, "litres"); if known == 0 { batch = OilBatch() } }
                if type == "RECEIPT" || type == "TRANSFER" { known = (number(event, "previous_litres") ?? known ?? 0) + (number(event, "litres") ?? 0) }
            }
            if source && text(event, "type") == "TRANSFER", let amount = number(event, "litres"), let old = known { known = old - amount; if known == 0 { batch = OilBatch() } }
        }
        return batch
    }
    static func validateMeasurements(_ row: Row) throws {
        for (key, maxValue) in [("brix", Decimal(100)), ("fat_percent", Decimal(100)), ("ph", Decimal(14))] {
            if let value = row[key] as? String { guard let n = decimal(value), n >= 0, n <= maxValue else { throw MilkywayError.message("Nieprawidłowy parametr \(key).") } }
        }
        if let value = row["sh"] as? String { try require((decimal(value) ?? -1) >= 0, "SH nie może być ujemne.") }
        if let value = row["temperature"] as? String { try require(decimal(value) != nil, "Wpisz poprawną temperaturę.") }
    }
    static func validateTank(_ row: Row, tank: Tank) throws {
        if let l = number(row, "litres") { try require(l >= 0 && (tank.capacityLitres == nil || l <= Decimal(tank.capacityLitres!)), "Ilość przekracza pojemność zbiornika lub jest ujemna.") }
        try validateMeasurements(row)
        try require(text(row, "material").count <= 200 && text(row, "oil_type").count <= 120, "Opis zawartości jest za długi.")
        try require(row["evaporator"] == nil || ["C", "F"].contains(text(row, "evaporator")), "Wybierz wyparkę C lub F.")
        try require(row["crystallizer"] == nil || (1...6).contains(integer(row, "crystallizer")), "Wybierz krystalizator 1–6.")
    }
    public static func editTank(_ doc: inout SharedDocument, tank: Tank, expected: Row, fields: Row, cubicMetres: String, batch: OilBatch?, laboratory: Bool, role: String, requestID: String, now: Date) throws {
        if doc.row("movements", requestID) != nil { return }
        if let batch {
            try require(batch.notes.count <= 1000 && (batch.produced.isEmpty || ProductionClock.date(batch.produced) != nil) && (batch.expires.isEmpty || ProductionClock.date(batch.expires) != nil) && (batch.produced.isEmpty || batch.expires.isEmpty || batch.expires >= batch.produced), "Sprawdź daty partii i długość uwag.")
        }
        var old = doc.row("tank_states", tank.id) ?? ["tank_id": tank.id, "material": "", "oil_type": ""]
        try require(equalRows(old, expected), "Zbiornik został zmieniony. Otwórz edycję ponownie.")
        var row = old
        for key in ["brix", "fat_percent", "ph", "sh", "temperature", "material", "oil_type", "evaporator", "crystallizer"] {
            if let value = fields[key] { row[key] = value } else { row.removeValue(forKey: key) }
        }
        if !cubicMetres.isEmpty {
            guard let cubic = decimal(cubicMetres), cubic >= 0 else { throw MilkywayError.message("Wpisz poprawną ilość w m³.") }
            let litres = try amount(string(cubic * 1000), positive: false); row["litres"] = string(litres)
        }
        try validateTank(row, tank: tank)
        if laboratory { try require(role == "laboratory", "To konto nie ma uprawnień Laboratorium."); row["laboratory_at"] = timestamp(now) }
        else if ["brix", "fat_percent", "ph", "sh", "temperature"].contains(where: { text(old, $0) != text(row, $0) }) { row.removeValue(forKey: "laboratory_at") }
        let qty = number(row, "litres"); let previous = number(old, "litres")
        if qty == 0 { row.removeValue(forKey: "filled_at"); row.removeValue(forKey: "laboratory_at") }
        else if let q = qty, previous == nil || q > previous! { row["filled_at"] = timestamp(now) }
        row["tank_id"] = tank.id; row["material"] = text(row, "material"); row["oil_type"] = text(row, "oil_type")
        var event = row; event.removeValue(forKey: "tank_id"); event.removeValue(forKey: "_rowid"); event.removeValue(forKey: "_actor"); event.removeValue(forKey: "filled_at")
        event["id"] = requestID; event["type"] = qty == nil ? "DETAILS" : "SET_STATE"; event["target_id"] = tank.id
        event["litres"] = string(qty ?? 0); event["previous_litres"] = old["litres"]; event["occurred_at"] = timestamp(now); event["note"] = "Ręczna edycja zbiornika."
        doc.put("tank_states", id: tank.id, row: row); doc.put("movements", id: requestID, row: event)
        if tank.oil, var batch {
            if qty == 0 { batch.produced = ""; batch.expires = "" }
            event["id"] = requestID + ":1"; event["type"] = "OIL_TYPE"; event["litres"] = "0"; event["previous_litres"] = row["litres"]
            event["note"] = "Poprzedni rodzaj oleju: \(text(old, "oil_type", "niepodany"))" + batch.suffix
            doc.put("movements", id: requestID + ":1", row: event)
        }
        old.removeAll()
    }
    public static func addMaterial(_ doc: inout SharedDocument, target: Tank, source: Tank?, cubicMetres: String, external: String, note: String, requestID: String, now: Date) throws {
        if doc.row("movements", requestID) != nil { return }
        guard let cubic = decimal(cubicMetres) else { throw MilkywayError.message("Wpisz ilość w m³.") }
        let qty = try amount(string(cubic * 1000))
        var row = doc.row("tank_states", target.id) ?? ["tank_id": target.id, "material": "", "oil_type": ""]
        guard let old = number(row, "litres") else { throw MilkywayError.message("Najpierw ustal ilość w zbiorniku docelowym.") }
        row["litres"] = string(old + qty); row["filled_at"] = timestamp(now); row.removeValue(forKey: "laboratory_at"); try validateTank(row, tank: target)
        var event: Row = ["id": requestID, "type": source == nil ? "RECEIPT" : "TRANSFER", "target_id": target.id, "external_source": external, "litres": string(qty), "previous_litres": string(old), "occurred_at": timestamp(now), "note": note, "material": text(row, "material"), "oil_type": text(row, "oil_type")]
        if let source {
            try require(source.id != target.id, "Wybierz inny zbiornik źródłowy.")
            guard var sourceRow = doc.row("tank_states", source.id), let sourceQty = number(sourceRow, "litres") else { throw MilkywayError.message("Ustal ilość w zbiorniku źródłowym.") }
            try require(sourceQty >= qty, "Brakuje materiału w zbiorniku źródłowym.")
            sourceRow["litres"] = string(sourceQty - qty); if sourceQty == qty { sourceRow.removeValue(forKey: "filled_at"); sourceRow.removeValue(forKey: "laboratory_at") }
            doc.put("tank_states", id: source.id, row: sourceRow); event["source_id"] = source.id
        } else { try require(!external.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "Podaj skąd dolano materiał.") }
        doc.put("tank_states", id: target.id, row: row); doc.put("movements", id: requestID, row: event)
    }
}
