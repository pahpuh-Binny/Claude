#include "webui.h"

#include <ArduinoJson.h>
#include <SD.h>
#include <Update.h>
#include <WebServer.h>
#include <WiFi.h>
#include <time.h>

#include "app.h"
#include "assistant.h"
#include "netconn.h"
#include "news.h"
#include "photos.h"
#include "settings.h"
#include "web_index.h"
#include "web_setup.h"

namespace webui {

static WebServer server(80);
static const char* WEB_USER = "family";

static bool allowed() {
    if (cfg.webPass.isEmpty() || netconn::setupMode()) return true;
    if (server.authenticate(WEB_USER, cfg.webPass.c_str())) return true;
    server.requestAuthentication(BASIC_AUTH, "Family Frame");
    return false;
}

static void sendJson(int code, JsonDocument& doc) {
    String out;
    serializeJson(doc, out);
    server.sendHeader("Cache-Control", "no-store");
    server.send(code, "application/json", out);
}

static void sendOk() {
    server.send(200, "application/json", "{\"ok\":true}");
}

static void sendError(int code, const String& msg) {
    JsonDocument doc;
    doc["error"] = msg;
    sendJson(code, doc);
}

static bool readBody(JsonDocument& doc) {
    if (deserializeJson(doc, server.arg("plain"))) {
        sendError(400, "Bad request");
        return false;
    }
    return true;
}

// Keeps letters, digits, dot, dash and underscore; always ends in .jpg.
static String safePhotoName(const String& in) {
    String base;
    int dot = in.lastIndexOf('.');
    String stem = dot > 0 ? in.substring(0, dot) : in;
    for (size_t i = 0; i < stem.length() && base.length() < 40; i++) {
        char c = stem[i];
        if (isalnum((unsigned char)c) || c == '-' || c == '_') base += c;
    }
    if (base.isEmpty()) base = "photo";
    String name = base + ".jpg";
    for (int n = 2; SD.exists(String(photos::DIR) + "/" + name) && n < 1000; n++) name = base + "-" + n + ".jpg";
    return name;
}

// ---------------- photo upload ----------------

static File uploadFile;
static String uploadName;
static bool uploadOk = false;

static void onUploadData() {
    HTTPUpload& up = server.upload();
    if (up.status == UPLOAD_FILE_START) {
        uploadOk = cfg.webPass.isEmpty() || server.authenticate(WEB_USER, cfg.webPass.c_str());
        if (!uploadOk) return;
        uploadName = safePhotoName(up.filename);
        uploadFile = SD.open(String(photos::DIR) + "/" + uploadName, FILE_WRITE);
        uploadOk = (bool)uploadFile;
    } else if (up.status == UPLOAD_FILE_WRITE) {
        if (uploadOk && uploadFile.write(up.buf, up.currentSize) != up.currentSize) uploadOk = false;
    } else if (up.status == UPLOAD_FILE_END) {
        if (uploadFile) uploadFile.close();
        if (!uploadOk || up.totalSize < 100) {
            SD.remove(String(photos::DIR) + "/" + uploadName);
            uploadOk = false;
        }
    } else if (up.status == UPLOAD_FILE_ABORTED) {
        if (uploadFile) uploadFile.close();
        SD.remove(String(photos::DIR) + "/" + uploadName);
        uploadOk = false;
    }
}

static void onUploadDone() {
    if (!allowed()) return;
    if (!uploadOk) return sendError(500, "The photo couldn't be saved. Is the SD card full?");
    app::photoUploaded(uploadName);
    JsonDocument doc;
    doc["ok"] = true;
    doc["name"] = uploadName;
    sendJson(200, doc);
}

// ---------------- firmware update ----------------

static bool otaOk = false;
static String otaError;

static void onFirmwareData() {
    HTTPUpload& up = server.upload();
    if (up.status == UPLOAD_FILE_START) {
        otaError = "";
        otaOk = cfg.webPass.isEmpty() || server.authenticate(WEB_USER, cfg.webPass.c_str());
        if (!otaOk) {
            otaError = "Not allowed";
            return;
        }
        otaOk = Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH);
        if (!otaOk) otaError = Update.errorString();
    } else if (up.status == UPLOAD_FILE_WRITE) {
        if (otaOk && Update.write(up.buf, up.currentSize) != up.currentSize) {
            otaOk = false;
            otaError = Update.errorString();
        }
    } else if (up.status == UPLOAD_FILE_END) {
        if (otaOk && !Update.end(true)) {
            otaOk = false;
            otaError = Update.errorString();
        }
    } else if (up.status == UPLOAD_FILE_ABORTED) {
        Update.abort();
        otaOk = false;
        otaError = "Upload interrupted";
    }
}

static void onFirmwareDone() {
    if (!otaOk) {
        return sendError(400, "Update failed: " + otaError +
                                  ". Use the file named family-frame-...-update.bin (not the factory file).");
    }
    sendOk();
    app::requestReboot();
}

// ---------------- routes ----------------

static void handleStatus() {
    if (!allowed()) return;
    JsonDocument doc;
    doc["version"] = FW_VERSION;
    doc["ip"] = netconn::ip();
    doc["hostname"] = cfg.hostname;
    doc["ssid"] = cfg.wifiSsid;
    doc["online"] = netconn::online();
    if (netconn::online()) doc["rssi"] = WiFi.RSSI();
    doc["photos"] = photos::count();
    doc["sdTotalMB"] = (uint32_t)(SD.totalBytes() / (1024 * 1024));
    doc["sdUsedMB"] = (uint32_t)(SD.usedBytes() / (1024 * 1024));
    doc["heapKB"] = ESP.getFreeHeap() / 1024;
    doc["uptimeMin"] = millis() / 60000;
    doc["newsBusy"] = news::busy();
    if (netconn::timeValid()) {
        time_t now = time(nullptr);
        struct tm t;
        localtime_r(&now, &t);
        char buf[32];
        strftime(buf, sizeof buf, "%a %b %d, %I:%M %p", &t);
        doc["time"] = buf;
    }
    news::Weather w = news::weather();
    if (w.valid) doc["weather"] = news::weatherSentence(false);
    sendJson(200, doc);
}

static void handleGetSettings() {
    if (!allowed()) return;
    JsonDocument doc;
    settings::toJson(doc.to<JsonObject>(), false);
    sendJson(200, doc);
}

static void handlePostSettings() {
    if (!allowed()) return;
    JsonDocument doc;
    if (!readBody(doc)) return;
    bool wifiChanged = false;
    settings::fromJson(doc.as<JsonObjectConst>(), true, &wifiChanged);
    if (!settings::save()) return sendError(500, "Couldn't save to the SD card.");
    app::settingsChanged(wifiChanged);
    sendOk();
}

static void handlePhotos() {
    if (!allowed()) return;
    server.sendHeader("Cache-Control", "no-store");
    server.setContentLength(CONTENT_LENGTH_UNKNOWN);
    server.send(200, "application/json", "");
    String chunk = "{\"count\":" + String(photos::count()) + ",\"names\":[";
    bool first = true;
    photos::forEachName([&](const String& name) {
        if (name.indexOf('"') >= 0 || name.indexOf('\\') >= 0) return;  // can't be listed safely
        if (!first) chunk += ',';
        first = false;
        chunk += '"';
        chunk += name;
        chunk += '"';
        if (chunk.length() > 1024) {
            server.sendContent(chunk);
            chunk = "";
        }
    });
    chunk += "]}";
    server.sendContent(chunk);
    server.sendContent("");
}

static bool validName(const String& n) {
    return n.length() && n.indexOf('/') < 0 && n.indexOf('\\') < 0 && n.indexOf('"') < 0 && !n.startsWith(".");
}

static void handlePhotoFile() {
    if (!allowed()) return;
    String name = server.arg("name");
    if (!validName(name)) return sendError(400, "Bad name");
    File f = SD.open(String(photos::DIR) + "/" + name, FILE_READ);
    if (!f) return sendError(404, "Not found");
    server.sendHeader("Cache-Control", "max-age=86400");
    server.streamFile(f, "image/jpeg");
    f.close();
}

static void handleDelete() {
    if (!allowed()) return;
    String name = server.arg("name");
    if (!validName(name)) return sendError(400, "Bad name");
    if (!photos::remove(name)) return sendError(404, "Couldn't delete that photo.");
    app::photosChanged();
    sendOk();
}

static void handleShow() {
    if (!allowed()) return;
    String name = server.arg("name");
    if (!validName(name)) return sendError(400, "Bad name");
    app::requestShowPhoto(name);
    sendOk();
}

static void handleNews() {
    if (!allowed()) return;
    JsonDocument doc;
    doc["busy"] = news::busy();
    JsonArray arr = doc["feeds"].to<JsonArray>();
    for (int i = 0; i < cfg.feedCount; i++) {
        news::Feed f = news::feed(i);
        JsonObject o = arr.add<JsonObject>();
        o["name"] = cfg.feeds[i].name;
        o["on"] = cfg.feeds[i].enabled;
        o["status"] = f.status;
        o["updated"] = f.updated;
        JsonArray items = o["items"].to<JsonArray>();
        for (int k = 0; k < f.count; k++) {
            JsonObject it = items.add<JsonObject>();
            it["t"] = f.items[k].title;
            it["s"] = f.items[k].source;
        }
    }
    sendJson(200, doc);
}

static void handleAsk() {
    if (!allowed()) return;
    JsonDocument doc;
    if (!readBody(doc)) return;
    String q = doc["text"] | "";
    q.trim();
    if (q.isEmpty()) return sendError(400, "Type a question first.");
    if (cfg.aiKey.isEmpty()) return sendError(400, "Add your AI key and save first.");
    String answer, err;
    if (!assistant::ask(q, answer, err)) return sendError(502, err);
    if (cfg.speakAnswers) app::requestSay(answer);
    JsonDocument out;
    out["answer"] = answer;
    sendJson(200, out);
}

static void handleSay() {
    if (!allowed()) return;
    JsonDocument doc;
    if (!readBody(doc)) return;
    app::requestSay(doc["text"] | "");
    sendOk();
}

static void handleScan() {
    if (!allowed()) return;
    int n = WiFi.scanNetworks();
    JsonDocument doc;
    JsonArray arr = doc["networks"].to<JsonArray>();
    for (int i = 0; i < n && i < 30; i++) {
        String ssid = WiFi.SSID(i);
        if (ssid.isEmpty()) continue;
        bool dup = false;
        for (JsonObject o : arr) dup |= ssid == (const char*)o["ssid"];
        if (dup) continue;
        JsonObject o = arr.add<JsonObject>();
        o["ssid"] = ssid;
        o["rssi"] = WiFi.RSSI(i);
        o["open"] = WiFi.encryptionType(i) == WIFI_AUTH_OPEN;
    }
    WiFi.scanDelete();
    sendJson(200, doc);
}

static void action(const char* path, void (*fn)()) {
    server.on(path, HTTP_POST, [fn]() {
        if (!allowed()) return;
        fn();
        sendOk();
    });
}

void begin() {
    server.on("/", HTTP_GET, []() {
        if (netconn::setupMode()) return server.send_P(200, "text/html", SETUP_HTML);
        if (!allowed()) return;
        server.send_P(200, "text/html", INDEX_HTML);
    });
    server.on("/api/status", HTTP_GET, handleStatus);
    server.on("/api/settings", HTTP_GET, handleGetSettings);
    server.on("/api/settings", HTTP_POST, handlePostSettings);
    server.on("/api/photos", HTTP_GET, handlePhotos);
    server.on("/photo", HTTP_GET, handlePhotoFile);
    server.on("/api/upload", HTTP_POST, onUploadDone, onUploadData);
    server.on("/api/delete", HTTP_POST, handleDelete);
    server.on("/api/show", HTTP_POST, handleShow);
    server.on("/api/news", HTTP_GET, handleNews);
    server.on("/api/ask", HTTP_POST, handleAsk);
    server.on("/api/say", HTTP_POST, handleSay);
    server.on("/api/scan", HTTP_GET, handleScan);
    server.on("/api/update", HTTP_POST, onFirmwareDone, onFirmwareData);
    action("/api/refresh", []() { news::requestRefresh(); });
    action("/api/briefing", app::requestBriefing);
    action("/api/test/mic", app::requestMicTest);
    action("/api/test/speaker", app::requestSpeakerTest);
    action("/api/touch-setup", app::requestTouchSetup);
    action("/api/reboot", app::requestReboot);
    server.onNotFound([]() {
        if (netconn::setupMode()) {
            // Captive portal: send phones' "is there internet?" checks to the setup page.
            server.sendHeader("Location", "http://" + netconn::ip() + "/", true);
            server.send(302, "text/plain", "");
            return;
        }
        server.send(404, "text/plain", "Not found");
    });
    const char* headers[] = {"Authorization"};
    server.collectHeaders(headers, 1);
    server.begin();
}

void loop() { server.handleClient(); }

}  // namespace webui
