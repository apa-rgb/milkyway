import Foundation

public typealias Row = [String: Any]
public enum MilkywayError: LocalizedError {
    case message(String)
    public var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}
public func require(_ condition: Bool, _ message: String) throws {
    if !condition { throw MilkywayError.message(message) }
}
public func text(_ row: Row, _ key: String, _ fallback: String = "") -> String { row[key] as? String ?? fallback }
public func integer(_ row: Row, _ key: String) -> Int64 { (row[key] as? NSNumber)?.int64Value ?? 0 }
public func decimal(_ value: String) -> Decimal? {
    let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
    guard normalized.range(of: "^-?[0-9]+(?:\\.[0-9]+)?$", options: .regularExpression) != nil else { return nil }
    return Decimal(string: normalized, locale: Locale(identifier: "en_US_POSIX"))
}
public func number(_ row: Row, _ key: String) -> Decimal? { decimal(text(row, key)) }
public func string(_ value: Decimal) -> String { NSDecimalNumber(decimal: value).stringValue }
public func amount(_ value: String, positive: Bool = true) throws -> Decimal {
    guard let result = decimal(value) else { throw MilkywayError.message("Wpisz poprawną ilość.") }
    try require(positive ? result > 0 : result >= 0, positive ? "Ilość musi być większa od zera." : "Ilość nie może być ujemna.")
    let canonical = string(result)
    try require(canonical.filter(\.isNumber).count <= 24 && (canonical.split(separator: ".").dropFirst().first?.count ?? 0) <= 3,
                "Ilość podaj z dokładnością do 0,001 (do 24 cyfr).")
    return result
}
public func sharedKey(_ id: String) -> String {
    Data(id.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
}
public func equalRows(_ lhs: Row, _ rhs: Row) -> Bool {
    guard let first = try? JSONSerialization.data(withJSONObject: lhs, options: [.sortedKeys]),
          let second = try? JSONSerialization.data(withJSONObject: rhs, options: [.sortedKeys]) else { return false }
    return first == second
}
public enum Domain: String, CaseIterable {
    case inventory, notes, production
    public var schema: Int { self == .production ? 2 : 1 }
    public var tables: [String] {
        switch self {
        case .inventory: return ["tank_states", "movements"]
        case .notes: return ["work_notes"]
        case .production: return ["production_queue", "production_completions", "production_product_notes", "production_rejects"]
        }
    }
}
/// Keeps unknown row fields and audit metadata intact while editing the latest server document.
public struct SharedDocument {
    public var root: Row
    public let domain: Domain
    public init(_ root: Row = [:], domain: Domain) throws {
        self.root = root; self.domain = domain
        if !root.isEmpty {
            let schema = integer(root, "schema")
            try require(schema == domain.schema || (domain == .production && schema == 1), "Zaktualizuj aplikację: nieobsługiwany format danych.")
            let tables = root["tables"] as? Row ?? [:]
            try require(Set(tables.keys).isSubset(of: Set(domain.tables)), "Nieobsługiwana tabela danych. Zaktualizuj aplikację.")
        }
    }
    public func rows(_ table: String) -> [Row] {
        let tables = root["tables"] as? Row ?? [:]
        let values = tables[table] as? Row ?? [:]
        return values.values.compactMap { $0 as? Row }.sorted { integer($0, "_rowid") < integer($1, "_rowid") }
    }
    public func row(_ table: String, _ id: String) -> Row? {
        let tables = root["tables"] as? Row ?? [:]
        return (tables[table] as? Row)?[sharedKey(id)] as? Row
    }
    public mutating func put(_ table: String, id: String, row: Row) {
        precondition(domain.tables.contains(table))
        var tables = root["tables"] as? Row ?? [:]
        var values = tables[table] as? Row ?? [:]
        var saved = row
        if saved["_rowid"] == nil { saved["_rowid"] = (rows(table).map { integer($0, "_rowid") }.max() ?? 0) + 1 }
        values[sharedKey(id)] = saved; tables[table] = values; root["tables"] = tables
    }
    public mutating func remove(_ table: String, id: String) {
        var tables = root["tables"] as? Row ?? [:]
        var values = tables[table] as? Row ?? [:]
        values.removeValue(forKey: sharedKey(id)); tables[table] = values; root["tables"] = tables
    }
    public mutating func commit(actor: Row, at: Int64, previous: SharedDocument) {
        for table in domain.tables {
            for old in rows(table) {
                let id = text(old, table == "tank_states" ? "tank_id" : "id")
                var comparison = old; comparison.removeValue(forKey: "_actor")
                var prior = previous.row(table, id) ?? [:]; prior.removeValue(forKey: "_actor")
                if !equalRows(comparison, prior) {
                    var changed = old; changed["_actor"] = actor.merging(["at": at]) { _, new in new }; put(table, id: id, row: changed)
                }
            }
        }
        root["schema"] = domain.schema; root["revision"] = integer(previous.root, "revision") + 1
        root["updatedAt"] = at; root["actor"] = actor
    }
}

public enum ProductionClock {
    public static var calendar: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "Europe/Warsaw")!; return c }
    public static func day(_ date: Date) -> String {
        let f = DateFormatter(); f.calendar = calendar; f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = calendar.timeZone; f.dateFormat = "yyyy-MM-dd"; return f.string(from: date)
    }
    public static func date(_ day: String) -> Date? {
        let f = DateFormatter(); f.calendar = calendar; f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = calendar.timeZone; f.dateFormat = "yyyy-MM-dd"; f.isLenient = false
        guard let d = f.date(from: day), self.day(d) == day else { return nil }; return d
    }
    public static func warehouseDay(_ now: Date) -> String {
        day(calendar.component(.hour, from: now) < 9 ? calendar.date(byAdding: .day, value: -1, to: now)! : now)
    }
    public static func canComplete(_ day: String, now: Date) -> Bool { day == self.day(now) || day == warehouseDay(now) }
    public static func code(_ day: String) -> String {
        guard let d = date(day) else { return "" }; return String(format: "%03d", calendar.ordinality(of: .day, in: .year, for: d)!)
    }
}
public struct Reminder: Equatable {
    public var at: Int64; public var token: String; public var readers: Set<String>; public var done: Bool
    public init(at: Int64, token: String = UUID().uuidString.lowercased(), readers: Set<String> = [], done: Bool = false) {
        self.at = at; self.token = token; self.readers = readers; self.done = done
    }
    public func due(now: Int64, account: String) -> Bool { at <= now && !done && !readers.contains(account) }
}
public struct Annotation {
    public var body = ""; public var important = false; public var author = ""; public var completed = false; public var reminder: Reminder?
    public init(_ text: String = "") {
        var lines = text.components(separatedBy: "\n")
        if lines.first == "[Ważne]" { important = true; lines.removeFirst() }
        if let first = lines.first, first.hasPrefix("[Autor: "), first.hasSuffix("]") {
            let name = String(first.dropFirst(8).dropLast())
            if !name.isEmpty && name.count <= 60 && !name.contains("[") && !name.contains("]") { author = name; lines.removeFirst() }
        }
        if let first = lines.first, first.range(of: "^\\[Przypomnienie: [0-9]{1,15}\\|[a-f0-9-]{36}\\|[0-9a-z,]*\\|[01]\\]$", options: .regularExpression) != nil {
            let fields = String(first.dropFirst(16).dropLast()).components(separatedBy: "|")
            let readers = Set(fields[2].split(separator: ",").map(String.init))
            if let at = Int64(fields[0]), at > 0, readers.allSatisfy({ $0 == "local" || (1...10).map { String(format: "%02d", $0) }.contains($0) }) {
                reminder = Reminder(at: at, token: fields[1], readers: readers, done: fields[3] == "1"); lines.removeFirst()
            }
        }
        if lines.first == "[Załatwione]" { completed = true; lines.removeFirst() }
        body = lines.joined(separator: "\n")
    }
    public var encoded: String {
        var lines: [String] = []
        if important { lines.append("[Ważne]") }
        let name = author.replacingOccurrences(of: "[", with: "").replacingOccurrences(of: "]", with: "").replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\r", with: " ")
        if !name.isEmpty { lines.append("[Autor: \(name.prefix(60))]") }
        if let r = reminder { lines.append("[Przypomnienie: \(r.at)|\(r.token)|\(r.readers.sorted().joined(separator: ","))|\(r.done ? 1 : 0)]") }
        if completed { lines.append("[Załatwione]") }
        lines.append(body.trimmingCharacters(in: .whitespacesAndNewlines)); return lines.joined(separator: "\n")
    }
}

public struct Tank: Codable, Identifiable {
    public let id: String; public let name: String; public let description: String; public let capacityLitres: Int?; public let group: String
    public var oil: Bool { group == "Oleje" }; public var butter: Bool { group == "Masłownia" }; public var powder: Bool { group.hasPrefix("Proszkownia") }
}
public struct OilBatch: Equatable {
    public var produced = ""; public var expires = ""; public var notes = ""
    public init(produced: String = "", expires: String = "", notes: String = "") { self.produced = produced; self.expires = expires; self.notes = notes }
    public init?(audit: String) {
        let marker = "\n[milkyway-oil-batch:v1]"
        guard let range = audit.range(of: marker, options: .backwards) else { return nil }
        let fields = audit[range.upperBound...].components(separatedBy: "|")
        guard fields.count == 2, fields.allSatisfy({ $0.isEmpty || ProductionClock.date($0) != nil }) else { return nil }
        produced = fields[0]; expires = fields[1]
        if let r = audit.range(of: "\n[milkyway-oil-notes:v1]", options: .backwards) {
            let encoded = String(audit[r.upperBound..<range.lowerBound])
            guard let data = Data(base64Encoded: encoded), let n = String(data: data, encoding: .utf8) else { return nil }; notes = n
        }
        guard notes.count <= 1000, produced.isEmpty || expires.isEmpty || expires >= produced else { return nil }
    }
    public var suffix: String { "\n[milkyway-oil-notes:v1]" + Data(notes.utf8).base64EncodedString() + "\n[milkyway-oil-batch:v1]\(produced)|\(expires)" }
    public func urgency(litres: Decimal?, now: Date) -> Int {
        guard let l = litres, l > 0, let expiry = ProductionClock.date(expires) else { return 0 }
        let c = ProductionClock.calendar
        let yesterday = c.date(byAdding: .day, value: -1, to: expiry)!
        let warning = c.date(bySettingHour: 12, minute: 0, second: 0, of: yesterday)!
        if now > warning { return 2 }
        return (c.dateComponents([.day], from: c.startOfDay(for: now), to: expiry).day ?? 999) < 3 ? 1 : 0
    }
}
