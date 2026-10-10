import Foundation
import SwiftUI
import Security

struct CloudConfig {
    let apiKey: String; let databaseURL: String; let plant: String; let project: String
    static var current: CloudConfig? {
        guard let url = Bundle.main.url(forResource: "MilkywayCloud", withExtension: "plist"),
              let data = try? Data(contentsOf: url), let p = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: String],
              let key = p["API_KEY"], !key.isEmpty, let database = p["DATABASE_URL"], database.hasPrefix("https://"),
              let plant = p["PLANT_ID"], !plant.isEmpty, p["PROJECT_ID"] == "milyway-41e9e" else { return nil }
        return CloudConfig(apiKey: key, databaseURL: database.trimmingCharacters(in: CharacterSet(charactersIn: "/")), plant: plant, project: p["PROJECT_ID"]!)
    }
}
enum SecureSession {
    static let service = "pl.apargb.milkyway.ios.session"
    static func read() -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: "refresh", kSecReturnData as String: true]
        var result: CFTypeRef?; guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    static func save(_ token: String?) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "refresh"]
        SecItemDelete(query as CFDictionary)
        guard let token else { return }
        var item = query; item[kSecValueData as String] = Data(token.utf8); item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        try require(SecItemAdd(item as CFDictionary, nil) == errSecSuccess, "Nie udało się bezpiecznie zapisać sesji.")
    }
}
@MainActor final class CloudStore: ObservableObject {
    @Published var signedIn = false
    @Published var busy = false
    @Published var error: String?
    @Published var account = ""
    @Published var name = ""
    @Published var role = ""
    @Published var ready: Set<Domain> = []
    @Published var documents: [Domain: SharedDocument] = [:]
    @Published var connection = "Łączenie…"
    let config = CloudConfig.current
    private var uid = ""; private var token = ""; private var refresh = ""; private var expires = Date.distantPast
    private var generation = UUID(); private var listeners: [Task<Void, Never>] = []
    private var refreshing: Task<Void, Error>?
    func document(_ domain: Domain) -> SharedDocument { documents[domain] ?? (try! SharedDocument(domain: domain)) }
    func rows(_ table: String, _ domain: Domain) -> [Row] { document(domain).rows(table) }
    func start() async {
        guard config != nil, let saved = SecureSession.read(), !saved.isEmpty else { return }
        busy = true; defer { busy = false }; refresh = saved
        do { try await renew(); try acceptClaims(); signedIn = true; name = UserDefaults.standard.string(forKey: "name:\(uid)") ?? ""; listen() }
        catch { try? SecureSession.save(nil); refresh = ""; error = "Zaloguj się ponownie. \(friendly(error))" }
    }
    func login(account: String, password: String, name: String) async {
        guard let config else { error = "Brak konfiguracji Firebase dla iPhone’a."; return }
        busy = true; defer { busy = false }
        do {
            let result = try await json(URL(string: "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=\(config.apiKey)")!, method: "POST", body: [
                "email": "konto\(account)@\(config.project).accounts.invalid", "password": password, "returnSecureToken": true])
            token = text(result, "idToken"); refresh = text(result, "refreshToken"); uid = text(result, "localId")
            expires = Date().addingTimeInterval(Double(text(result, "expiresIn")) ?? 3600); try acceptClaims(); try SecureSession.save(refresh)
            self.name = name.trimmingCharacters(in: .whitespacesAndNewlines); UserDefaults.standard.set(self.name, forKey: "name:\(uid)")
            signedIn = true; self.error = nil; listen()
        } catch { self.error = friendly(error); token = ""; refresh = ""; uid = "" }
    }
    func logout() {
        generation = UUID(); listeners.forEach { $0.cancel() }; listeners.removeAll(); refreshing?.cancel(); refreshing = nil
        signedIn = false; ready = []; documents = [:]; token = ""; refresh = ""; uid = ""; account = ""; role = ""; name = ""
        try? SecureSession.save(nil); LocalReminders.clear()
    }
    private func acceptClaims() throws {
        guard let config else { throw MilkywayError.message("Brak konfiguracji.") }
        let parts = token.split(separator: ".")
        try require(parts.count == 3, "Nieprawidłowa sesja.")
        var payload = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        payload += String(repeating: "=", count: (4 - payload.count % 4) % 4)
        guard let data = Data(base64Encoded: payload), let claims = try JSONSerialization.jsonObject(with: data) as? Row else { throw MilkywayError.message("Nieprawidłowa sesja.") }
        let account = text(claims, "account"); let role = text(claims, "role")
        try require(text(claims, "plant") == config.plant && (1...10).map { String(format: "%02d", $0) }.contains(account) && ["operator", "laboratory"].contains(role), "To konto nie ma dostępu do zakładu.")
        self.account = account; self.role = role
        // Claims only select the UI. Firebase rules independently authorize every read and write.
    }
    private func renew() async throws {
        guard let config, !refresh.isEmpty else { throw MilkywayError.message("Zaloguj się ponownie.") }
        let current = generation
        let result = try await json(URL(string: "https://securetoken.googleapis.com/v1/token?key=\(config.apiKey)")!, method: "POST", body: ["grant_type": "refresh_token", "refresh_token": refresh], form: true)
        try require(current == generation, "Sesja została zakończona.")
        token = text(result, "id_token"); refresh = text(result, "refresh_token"); uid = text(result, "user_id")
        expires = Date().addingTimeInterval(Double(text(result, "expires_in")) ?? 3600)
        try acceptClaims(); try SecureSession.save(refresh)
    }
    private func accessToken() async throws -> String {
        if expires.timeIntervalSinceNow < 120 {
            if let task = refreshing { try await task.value }
            else { let task = Task { try await renew() }; refreshing = task; defer { refreshing = nil }; try await task.value }
        }
        try require(!token.isEmpty, "Zaloguj się ponownie."); return token
    }
    private func url(_ domain: Domain) throws -> URL {
        guard let config else { throw MilkywayError.message("Brak konfiguracji.") }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        guard let plant = config.plant.addingPercentEncoding(withAllowedCharacters: allowed), let url = URL(string: "\(config.databaseURL)/plants/\(plant)/domains/\(domain.rawValue).json") else { throw MilkywayError.message("Nieprawidłowy adres bazy.") }
        return url
    }
    private func request(_ domain: Domain, method: String = "GET", body: Row? = nil, etag: String? = nil) async throws -> (Row, HTTPURLResponse) {
        let current = generation
        var components = URLComponents(url: try url(domain), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "auth", value: try await accessToken())]
        var request = URLRequest(url: components.url!); request.httpMethod = method; request.timeoutInterval = 20
        request.setValue("true", forHTTPHeaderField: "X-Firebase-ETag")
        if let etag { request.setValue(etag, forHTTPHeaderField: "if-match") }
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await URLSession.shared.data(for: request)
        try require(current == generation && signedIn, "Sesja została zakończona.")
        guard let response = response as? HTTPURLResponse else { throw MilkywayError.message("Brak odpowiedzi serwera.") }
        let object = try JSONSerialization.jsonObject(with: data, options: .fragmentsAllowed) as? Row ?? [:]
        if response.statusCode == 412 { return (object, response) }
        try require((200...299).contains(response.statusCode), response.statusCode == 401 || response.statusCode == 403 ? "Brak dostępu do bazy. Sprawdź konto i konfigurację Firebase." : "Nie udało się połączyć z bazą (\(response.statusCode)).")
        return (object, response)
    }
    private func listen() {
        listeners.forEach { $0.cancel() }; listeners.removeAll(); let current = generation
        for domain in Domain.allCases {
            listeners.append(Task { [weak self] in
                guard let self else { return }
                while !Task.isCancelled && current == generation {
                    do {
                        let (root, _) = try await request(domain); documents[domain] = try SharedDocument(root, domain: domain); ready.insert(domain)
                        var components = URLComponents(url: try url(domain), resolvingAgainstBaseURL: false)!
                        components.queryItems = [URLQueryItem(name: "auth", value: try await accessToken())]
                        var request = URLRequest(url: components.url!); request.timeoutInterval = 300
                        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                        let (bytes, response) = try await URLSession.shared.bytes(for: request)
                        try require((response as? HTTPURLResponse)?.statusCode == 200, "Brak połączenia z aktualizacjami.")
                        connection = "Dane na żywo"
                        var event = ""
                        for try await line in bytes.lines {
                            try Task.checkCancellation(); guard current == generation else { return }
                            if line.hasPrefix("event: ") { event = String(line.dropFirst(7)) }
                            if line.isEmpty && ["put", "patch"].contains(event) {
                                let (root, _) = try await self.request(domain); documents[domain] = try SharedDocument(root, domain: domain)
                                LocalReminders.sync(store: self); event = ""
                            }
                            if line.isEmpty && ["cancel", "auth_revoked"].contains(event) { throw MilkywayError.message("Sesja aktualizacji została przerwana.") }
                        }
                    } catch { if Task.isCancelled || current != generation { return }; connection = "Brak połączenia — ponawiam"; self.error = friendly(error) }
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                }
            })
        }
    }
    /// ETag + If-Match is Firebase REST's transaction protocol. Retry against fresh server state.
    func mutate(_ domain: Domain, _ change: (inout SharedDocument) throws -> Void) async throws {
        try require(signedIn && ready.contains(domain), "Poczekaj na załadowanie danych.")
        let current = generation; let actor: Row = ["uid": uid, "account": account]
        for _ in 0..<6 {
            let (root, response) = try await request(domain)
            guard let etag = response.value(forHTTPHeaderField: "ETag") else { throw MilkywayError.message("Serwer nie udostępnił wersji danych. Zapis został przerwany.") }
            let old = try SharedDocument(root, domain: domain); var updated = old; try change(&updated)
            if equalRows(old.root, updated.root) { return }
            updated.commit(actor: actor, at: Operations.timestamp(Date()), previous: old)
            try require(current == generation, "Sesja została zakończona.")
            let (saved, result) = try await request(domain, method: "PUT", body: updated.root, etag: etag)
            if result.statusCode == 412 { continue }
            documents[domain] = try SharedDocument(saved, domain: domain); LocalReminders.sync(store: self); return
        }
        throw MilkywayError.message("Dane zmieniły się podczas zapisu. Spróbuj ponownie.")
    }
    func run(_ action: @escaping () async throws -> Void) {
        guard !busy else { return }; busy = true
        Task { defer { busy = false }; do { try await action() } catch { self.error = friendly(error) } }
    }
    private func json(_ url: URL, method: String, body: Row, form: Bool = false) async throws -> Row {
        var request = URLRequest(url: url); request.httpMethod = method; request.timeoutInterval = 20
        if form {
            var components = URLComponents(); components.queryItems = body.map { URLQueryItem(name: $0.key, value: $0.value as? String) }
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data((components.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B").utf8)
        } else { request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (data, response) = try await URLSession.shared.data(for: request)
        let result = try JSONSerialization.jsonObject(with: data) as? Row ?? [:]
        try require((200...299).contains((response as? HTTPURLResponse)?.statusCode ?? 0), text(result["error"] as? Row ?? [:], "message", "Nie udało się zalogować."))
        return result
    }
    private func friendly(_ error: Error) -> String {
        let message = error.localizedDescription
        if ["INVALID_LOGIN_CREDENTIALS", "INVALID_PASSWORD", "EMAIL_NOT_FOUND"].contains(message) { return "Nieprawidłowe konto lub hasło." }
        if message.contains("TOO_MANY_ATTEMPTS") { return "Za dużo prób logowania. Spróbuj za chwilę." }
        return message
    }
}
