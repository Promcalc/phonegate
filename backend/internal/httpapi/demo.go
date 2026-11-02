package httpapi

import (
	"html/template"
	"net/http"
	"strings"
)

var demoTemplate = template.Must(template.New("demo").Parse(`<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>PhoneGate Demo — вход по SMS или звонку</title>
<link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet" integrity="sha384-QWTKZyjpPEjISv5WaRU9OFeRpok6YctnYmDr5pNlyT2bRjXh0JMhjY6hW+ALEwIH" crossorigin="anonymous">
<style>
:root{--bs-primary:#1266d6;--bs-success:#16803c;--bs-danger:#b42318;}
body{background:#f6f7f9}
.card{box-shadow:0 8px 30px rgba(0,0,0,.06);border-radius:16px}
.code-display{font:700 1.5rem/1.2 ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:2px}
.qr-img{width:200px;height:200px;image-rendering:pixelated}
.action-card{transition:all .2s ease}
.action-card.verified{border-color:var(--bs-success);background:rgba(22,128,60,.05)}
.action-card.error{border-color:var(--bs-danger);background:rgba(180,35,24,.05)}
.timer{font:700 1.25rem/1 ui-monospace,SFMono-Regular,Consolas,monospace}
@media (prefers-color-scheme:dark){
	body{background:#0f1115}
	.card{background:#171a20;border-color:#2a3039}
	.form-control{background:#0f1115;border-color:#2a3039;color:#f2f4f7}
	.form-control:focus{box-shadow:0 0 0 .25rem rgba(18,102,214,.25)}
	.text-muted{color:#a8b0bb!important}
}
</style>
</head>
<body>
<div class="container py-4" style="max-width:640px">
<div class="card p-4" id="app">

<div id="step-phone">
<h2 class="mb-1">PhoneGate Demo</h2>
<p class="text-muted mb-4">Введите номер телефона для авторизации через SMS или звонок</p>
<div class="mb-3">
<label for="phoneInput" class="form-label visually-hidden">Номер телефона</label>
<div class="input-group">
<span class="input-group-text" id="phonePrefix">+</span>
<input type="tel" id="phoneInput" class="form-control form-control-lg" placeholder="7 900 123 45 67" autocomplete="tel" inputmode="tel" aria-describedby="phonePrefix">
</div>
<div class="form-text text-muted">Формат: +7 900 123 45 67 или 8 900 123 45 67</div>
</div>
<div class="d-grid mb-3">
<button class="btn btn-primary btn-lg" id="btnStart">Получить код</button>
</div>
<div class="text-center text-danger small" id="phoneError" style="display:none"></div>
</div>

<div id="step-verify" style="display:none">
<h2 class="mb-1">Подтвердите номер</h2>
<p class="text-muted mb-2">Код отправлен. Выберите способ:</p>
<div class="d-flex justify-content-between align-items-center mb-3">
<div class="timer text-primary" id="timer">03:00</div>
<button class="btn btn-outline-secondary btn-sm" id="btnBack">Назад</button>
</div>

<div class="row g-3" id="actions">
<div class="col-12 col-md-6" id="action-sms">
<div class="card action-card h-100 p-3">
<h5 class="mb-2"><i class="bi bi-chat-text"></i> Отправить SMS</h5>
<p class="text-muted small mb-3">Нажмите кнопку — откроется приложение для SMS с заполненным номером и кодом. Нажмите «Отправить». QR-код на телефоне открывает страницу с той же кнопкой.</p>
<a class="btn btn-primary w-100 mb-2" id="linkSms" href="#" role="button">Открыть SMS</a>
<div class="text-center mb-2">
<img class="qr-img" id="qrSms" src="" alt="QR для SMS">
</div>
<div class="text-center text-danger small" id="errSms" style="display:none"></div>
<div class="text-center text-success small" id="okSms" style="display:none"></div>
</div>
</div>

<div class="col-12 col-md-6" id="action-call">
<div class="card action-card h-100 p-3">
<h5 class="mb-2"><i class="bi bi-telephone"></i> Позвонить</h5>
<p class="text-muted small mb-3">Нажмите кнопку — откроется телефон. Позвоните и сбросьте вызов. QR-код на телефоне открывает страницу с кнопкой звонка.</p>
<a class="btn btn-primary w-100 mb-2" id="linkCall" href="#" role="button">Открыть звонилку</a>
<div class="text-center mb-2">
<img class="qr-img" id="qrCall" src="" alt="QR для звонка">
</div>
<div class="text-center text-danger small" id="errCall" style="display:none"></div>
<div class="text-center text-success small" id="okCall" style="display:none"></div>
</div>
</div>
</div>

<div class="text-center text-muted small mt-3">
Страница не отправляет SMS и не звонит сама. Нажмите кнопку и подтвердите действие на телефоне.
</div>
</div>

</div>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js" integrity="sha384-YvpcrYf0tY3lHB60NNkmXc5s9fDVZLESaAA55NDzOxhy9GkcIdslK1eN7N6jIeHz" crossorigin="anonymous"></script>
<script>
(function(){
const API_BASE = '{{.ApiBase}}';
const POLL_MS = 3000;
const TIMER_MS = {{.TimerSec}} * 1000;

let timerInterval = null;
let pollInterval = null;
let currentTimerEnd = 0;
let verificationId = null;
let phoneNumber = null;
let gatewayNumber = null;
let smsCode = null;
let callCode = null;

const $ = function(sel) { return document.querySelector(sel); };
const show = function(el, show) { if(el) el.style.display = show ? '' : 'none'; };
const setText = function(el, text) { if(el) el.textContent = text; };
const setHref = function(el, href) { if(el) el.href = href; };
const setSrc = function(el, src) { if(el) el.src = src; };
const addClass = function(el, cls) { if(el) el.classList.add(cls); };
const removeClass = function(el, cls) { if(el) el.classList.remove(cls); };
const qs = function(sel) { return document.querySelector(sel); };
const qrWithKind = function(base, kind) {
	if (!base) return '';
	return base + (base.indexOf('?') >= 0 ? '&' : '?') + 'kind=' + encodeURIComponent(kind);
};

function fmtTime(ms) {
	var s = Math.max(0, Math.ceil(ms / 1000));
	var m = Math.floor(s / 60);
	var sec = s % 60;
	return (m < 10 ? '0' : '') + m + ':' + (sec < 10 ? '0' : '') + sec;
}

function startTimer(endMs) {
	currentTimerEnd = endMs;
	updateTimer();
	timerInterval = setInterval(updateTimer, 1000);
}
function updateTimer() {
	var rem = currentTimerEnd - Date.now();
	if (rem <= 0) {
		stopTimer();
		resetToPhone();
		return;
	}
	setText(qs('#timer'), fmtTime(rem));
}
function stopTimer() {
	if (timerInterval) { clearInterval(timerInterval); timerInterval = null; }
}
function stopPoll() {
	if (pollInterval) { clearInterval(pollInterval); pollInterval = null; }
}

function resetToPhone() {
	stopTimer(); stopPoll();
	verificationId = null; gatewayNumber = null; smsCode = null; callCode = null;
	show(qs('#step-phone'), true);
	show(qs('#step-verify'), false);
	qs('#phoneInput').value = '';
	qs('#phoneError').style.display = 'none';
}

function showStepVerify(data) {
	verificationId = data.id;
	gatewayNumber = data.gateway_number;
	smsCode = data.code;
	var expiresAt = new Date(data.expires_at).getTime();
	startTimer(expiresAt);

	setHref(qs('#linkSms'), data.links && data.links.sms_uri ? data.links.sms_uri : '#');
	setHref(qs('#linkCall'), data.links && data.links.tel_uri ? data.links.tel_uri : '#');
	setSrc(qs('#qrSms'), data.links && data.links.sms_qr_url ? data.links.sms_qr_url : qrWithKind(data.links && data.links.qr_url, 'sms'));
	setSrc(qs('#qrCall'), data.links && data.links.tel_qr_url ? data.links.tel_qr_url : qrWithKind(data.links && data.links.qr_url, 'call'));

	qs('#action-sms').style.display = data.method === 'call' ? 'none' : '';
	qs('#action-call').style.display = data.method === 'sms' ? 'none' : '';

	if (data.method === 'any') {
		qs('#action-sms').style.display = '';
		qs('#action-call').style.display = '';
	}

	['errSms','okSms','errCall','okCall'].forEach(function(id) { show(qs('#' + id), false); });
	removeClass(qs('#action-sms'), 'verified'); removeClass(qs('#action-sms'), 'error');
	removeClass(qs('#action-call'), 'verified'); removeClass(qs('#action-call'), 'error');

	show(qs('#step-phone'), false);
	show(qs('#step-verify'), true);
	startPoll();
}

async function apiRequest(path, options) {
	options = options || {};
	var url = API_BASE.replace(/\/+$/, '') + path;
	var headers = { 'Content-Type': 'application/json' };
	var apiKey = '{{.APIKey}}';
	if (apiKey) headers['Authorization'] = 'Bearer ' + apiKey;
	for (var k in options.headers) headers[k] = options.headers[k];
	var res = await fetch(url, { method: options.method || 'GET', headers: headers, body: options.body, cache: 'no-store' });
	var text = await res.text();
	var json = null;
	try { json = text ? JSON.parse(text) : null; } catch(e) {}
	if (!res.ok) {
		var msg = (json && json.error && json.error.message) ? json.error.message : ('HTTP ' + res.status);
		throw new Error(msg);
	}
	return json;
}

async function startVerification(phone) {
	qs('#btnStart').disabled = true;
	qs('#phoneError').style.display = 'none';
	try {
		var data = await apiRequest('/api/v1/verifications', {
			method: 'POST',
			body: JSON.stringify({ phone: phone, method: 'any' }),
			headers: {}
		});
		showStepVerify(data);
	} catch (e) {
		setText(qs('#phoneError'), e.message);
		qs('#phoneError').style.display = 'block';
	} finally {
		qs('#btnStart').disabled = false;
	}
}

function startPoll() {
	pollInterval = setInterval(async function() {
		if (!verificationId) return;
		try {
			var data = await apiRequest('/api/v1/verifications/' + verificationId);
			handleStatus(data);
		} catch (e) {
			console.warn('poll error:', e);
		}
	}, POLL_MS);
}

function handleStatus(data) {
	var status = data.status;
	if (status === 'verified') {
		stopTimer(); stopPoll();
		var isSms = data.method === 'sms' || data.method === 'any';
		var isCall = data.method === 'call' || data.method === 'any';
		if (isSms) { show(qs('#okSms'), true); setText(qs('#okSms'), '\u2713 Номер подтверждён по SMS'); addClass(qs('#action-sms'), 'verified'); }
		if (isCall) { show(qs('#okCall'), true); setText(qs('#okCall'), '\u2713 Номер подтверждён звонком'); addClass(qs('#action-call'), 'verified'); }
		setTimeout(resetToPhone, 3000);
	} else if (status === 'failed' || status === 'expired' || status === 'cancelled') {
		stopTimer(); stopPoll();
		var msg = status === 'failed' ? 'Ошибка: слишком много неверных попыток' :
			status === 'expired' ? 'Время вышло' : 'Заявка отменена';
		var isSms = data.method === 'sms' || data.method === 'any';
		var isCall = data.method === 'call' || data.method === 'any';
		if (isSms) { show(qs('#errSms'), true); setText(qs('#errSms'), msg); addClass(qs('#action-sms'), 'error'); }
		if (isCall) { show(qs('#errCall'), true); setText(qs('#errCall'), msg); addClass(qs('#action-call'), 'error'); }
		setTimeout(resetToPhone, 5000);
	}
}

function normalizePhone(raw) {
	var v = raw.trim().replace(/[\s\-\(\)]/g, '');
	if (!v) return '';
	if (v[0] !== '+') {
		if (v[0] === '8' && v.length === 11) v = '+7' + v.slice(1);
		else if (v[0] === '7' && v.length === 11) v = '+' + v;
		else if (v.length === 10) v = '+7' + v;
		else v = '+' + v;
	}
	return v;
}

function validatePhone(phone) {
	if (!/^\+\d{8,15}$/.test(phone)) return false;
	if (phone[1] === '0') return false;
	return true;
}

qs('#phoneInput').addEventListener('input', function(e) {
	var v = this.value.replace(/\D/g, '');
	if (!v) { this.value = ''; return; }
	if (v[0] === '8') v = '7' + v.slice(1);
	if (v[0] !== '7') v = '7' + v;
	if (v.length > 11) v = v.slice(0, 11);
	var formatted = '+';
	if (v.length > 1) formatted += v[0] + ' (' + v.slice(1, 4);
	if (v.length > 4) formatted += ') ' + v.slice(4, 7);
	if (v.length > 7) formatted += '-' + v.slice(7, 9);
	if (v.length > 9) formatted += '-' + v.slice(9, 11);
	this.value = formatted;
});

qs('#btnStart').addEventListener('click', function() {
	var raw = qs('#phoneInput').value;
	var normalized = normalizePhone(raw);
	if (!validatePhone(normalized)) {
		setText(qs('#phoneError'), 'Некорректный номер телефона');
		qs('#phoneError').style.display = 'block';
		return;
	}
	phoneNumber = normalized;
	startVerification(normalized);
});

qs('#btnBack').addEventListener('click', resetToPhone);

})();
</script>
</body>
</html>`))

func (s *Server) demoPage(w http.ResponseWriter, r *http.Request) {
	if s.svc.Links() == nil || !s.svc.Links().Enabled() {
		http.NotFound(w, r)
		return
	}
	// Find a demo tenant (first enabled tenant) to use its API key for the demo
	demoAPIKey := ""
	for _, tenant := range s.cfg.Tenants {
		if tenant.Enabled {
			// We need the plaintext key, but config only stores hash.
			// For demo, we can use a known test key from config.example.yaml
			// In production, this would be configured separately.
			demoAPIKey = "test-tenant-key" // matches config.example.yaml
			break
		}
	}
	data := map[string]any{
		"ApiBase":  strings.TrimRight(s.cfg.Server.PublicURL, "/"),
		"TimerSec": int(s.cfg.Verification.DefaultTTL.Std().Seconds()),
		"APIKey":   demoAPIKey,
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'self' https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; img-src 'self' data:; font-src https://cdn.jsdelivr.net; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	if err := demoTemplate.Execute(w, data); err != nil {
		s.log.Error("demo.page_render_failed", "error", err)
	}
}
