package httpapi

import (
	"errors"
	"html/template"
	"net/http"
	"net/url"
	"strings"

	qrcode "github.com/skip2/go-qrcode"

	"phonegate/backend/internal/code"
	"phonegate/backend/internal/links"
	"phonegate/backend/internal/model"
	"phonegate/backend/internal/phone"
)

type pageData struct {
	Locale        string
	Title         string
	Heading       string
	Instruction   string
	Code          string
	GatewayNumber string
	SMSURI        template.URL
	SMSURIIOS     template.URL
	TelURI        template.URL
	QRURL         string
	Status        string
	StatusText    string
	Expires       string
	PhoneMasked   string
	Pending       bool
	Verified      bool
	Failed        bool
}

var publicTemplate = template.Must(template.New("public").Parse(`<!doctype html>
<html lang="{{.Locale}}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>{{.Title}}</title>
<style>
:root{color-scheme:light dark;--bg:#f6f7f9;--card:#fff;--text:#111;--muted:#5b6470;--accent:#1266d6;--ok:#16803c;--err:#b42318;--line:#dfe3e8}
@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--card:#171a20;--text:#f2f4f7;--muted:#a8b0bb;--accent:#6ea8ff;--ok:#4ade80;--err:#ff8a80;--line:#2a3039}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:560px;margin:0 auto;padding:24px 16px 48px}.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:20px;box-shadow:0 8px 30px rgba(0,0,0,.06)}
h1{font-size:22px;margin:0 0 8px}p{margin:0 0 14px;color:var(--muted)}.code{font:700 30px/1.2 ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:2px;text-align:center;padding:16px;border:1px dashed var(--line);border-radius:12px;margin:16px 0;word-break:break-all}
.number{font:600 18px/1.3 ui-monospace,SFMono-Regular,Consolas,monospace;text-align:center;margin:8px 0 16px;word-break:break-all}
.buttons{display:grid;gap:10px;margin:16px 0}.button{display:block;text-align:center;text-decoration:none;padding:13px 16px;border-radius:12px;background:var(--accent);color:#fff;font-weight:600}
.button.secondary{background:transparent;color:var(--accent);border:1px solid var(--accent)}button.copy{cursor:pointer;font:inherit;border:0;border-radius:10px;padding:9px 12px;background:transparent;color:var(--accent);border:1px solid var(--line)}
.row{display:flex;gap:8px;justify-content:center;flex-wrap:wrap}.status{margin-top:16px;padding:12px;border-radius:10px;background:color-mix(in srgb,var(--accent) 10%,transparent);text-align:center;font-weight:600}
.status.ok{color:var(--ok)}.status.err{color:var(--err)}.muted{color:var(--muted);font-size:14px;text-align:center}.qr{display:block;max-width:240px;width:100%;margin:16px auto;image-rendering:pixelated}
</style>
</head>
<body><main><section class="card">
<h1>{{.Heading}}</h1>
<p>{{.Instruction}}</p>
{{if .Code}}<div class="code" id="code">{{.Code}}</div>{{end}}
<div class="number" id="number">{{.GatewayNumber}}</div>
<div class="buttons">
{{if .SMSURI}}<a class="button" href="{{.SMSURI}}" rel="noreferrer">Отправить SMS</a>{{end}}
{{if .TelURI}}<a class="button" href="{{.TelURI}}" rel="noreferrer">Позвонить</a>{{end}}
</div>
<div class="row">
{{if .Code}}<button class="copy" type="button" data-copy="{{.Code}}">Копировать код</button>{{end}}
<button class="copy" type="button" data-copy="{{.GatewayNumber}}">Копировать номер</button>
</div>
{{if .QRURL}}<img class="qr" src="{{.QRURL}}" width="240" height="240" alt="QR">{{end}}
<div class="status {{if .Verified}}ok{{else if .Failed}}err{{end}}" id="status">{{.StatusText}}</div>
<p class="muted" id="expires">Действует до {{.Expires}} UTC</p>
{{if .PhoneMasked}}<p class="muted">Номер: {{.PhoneMasked}}</p>{{end}}
<p class="muted">Страница не отправляет SMS и не звонит сама. Нажмите кнопку и подтвердите действие на телефоне.</p>
</section></main>
<script>
(function(){
  function copyText(text){
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
    var t=document.createElement('textarea'); t.value=text; t.style.position='fixed'; t.style.opacity='0';
    document.body.appendChild(t); t.select(); try{document.execCommand('copy');}catch(e){} document.body.removeChild(t);
  }
  document.querySelectorAll('[data-copy]').forEach(function(b){
    b.addEventListener('click', function(){ copyText(b.getAttribute('data-copy')||''); var old=b.textContent; b.textContent='Скопировано'; setTimeout(function(){b.textContent=old;},1200); });
  });
  var statusURL = window.location.pathname.replace(/\/+$/,'') + '/status';
  function render(s){
    var el=document.getElementById('status'); if(!el) return;
    el.className='status';
    if(s.status==='verified'){el.classList.add('ok'); el.textContent='Номер подтверждён';}
    else if(s.status==='failed'){el.classList.add('err'); el.textContent='Подтверждение не удалось';}
    else if(s.status==='expired'||s.status==='cancelled'){el.classList.add('err'); el.textContent='Ссылка больше не действует';}
    else {el.textContent='Ожидает подтверждения';}
  }
  function poll(){
    fetch(statusURL,{cache:'no-store',headers:{'Accept':'application/json'}}).then(function(r){
      if(!r.ok) throw new Error('status');
      return r.json();
    }).then(render).catch(function(){});
  }
  setInterval(poll, 3000);
})();
</script>
</body></html>`))

type actionPageData struct {
	Title         string
	Heading       string
	Description   string
	Button        string
	TargetURI     template.URL
	Code          string
	GatewayNumber string
	IsSMS         bool
}

var actionTemplate = template.Must(template.New("action").Parse(`<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>{{.Title}}</title>
<style>
:root{color-scheme:light dark;--bg:#f6f7f9;--card:#fff;--text:#111;--muted:#5b6470;--accent:#1266d6;--line:#dfe3e8}
@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--card:#171a20;--text:#f2f4f7;--muted:#a8b0bb;--accent:#6ea8ff;--line:#2a3039}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:520px;margin:0 auto;padding:24px 16px 48px}.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:20px;box-shadow:0 8px 30px rgba(0,0,0,.06)}
h1{font-size:22px;margin:0 0 10px}p{margin:0 0 16px;color:var(--muted)}.code{font:700 30px/1.2 ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:2px;text-align:center;padding:16px;border:1px dashed var(--line);border-radius:12px;margin:16px 0;word-break:break-all}
.number{font:600 18px/1.3 ui-monospace,SFMono-Regular,Consolas,monospace;text-align:center;margin:8px 0 18px;word-break:break-all}
.button{display:block;text-align:center;text-decoration:none;padding:14px 16px;border-radius:12px;background:var(--accent);color:#fff;font-weight:600;margin:18px 0 10px}
.muted{color:var(--muted);font-size:14px;text-align:center;margin:12px 0 0}
button.copy{cursor:pointer;font:inherit;border:0;border-radius:10px;padding:9px 12px;background:transparent;color:var(--accent);border:1px solid var(--line);display:block;margin:0 auto}
</style>
</head>
<body><main><section class="card">
<h1>{{.Heading}}</h1>
<p>{{.Description}}</p>
{{if .IsSMS}}{{if .Code}}<div class="code" id="code">{{.Code}}</div>{{end}}{{end}}
<div class="number">{{.GatewayNumber}}</div>
<a class="button" id="open" href="{{.TargetURI}}" rel="noreferrer">{{.Button}}</a>
{{if .IsSMS}}{{if .Code}}<button class="copy" type="button" data-copy="{{.Code}}">Копировать код</button>{{end}}{{end}}
<p class="muted">Если приложение не открылось автоматически, нажмите кнопку выше.</p>
</section></main>
<script>
(function(){
  function copyText(text){
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
    var t=document.createElement('textarea'); t.value=text; t.style.position='fixed'; t.style.opacity='0';
    document.body.appendChild(t); t.select(); try{document.execCommand('copy');}catch(e){} document.body.removeChild(t);
  }
  var copy=document.querySelector('[data-copy]');
  if(copy){copy.addEventListener('click',function(){copyText(copy.getAttribute('data-copy')||'');var old=copy.textContent;copy.textContent='Скопировано';setTimeout(function(){copy.textContent=old;},1200);});}
  var link=document.getElementById('open');
  var target=link?link.getAttribute('href'):'';
  if(!target) return;
  setTimeout(function(){ try{ window.location.href=target; }catch(e){} }, 350);
})();
</script>
</body></html>`))

func (s *Server) publicAction(w http.ResponseWriter, r *http.Request) {
	v, payload, err := s.resolvePublic(r, r.PathValue("token"))
	if err != nil {
		s.publicError(w, r, err)
		return
	}
	kind := strings.ToLower(strings.TrimSpace(r.PathValue("kind")))
	data := actionPageData{GatewayNumber: v.GatewayNumber}
	switch kind {
	case "sms":
		if !v.IsSMS() || payload.Code == "" {
			s.publicError(w, r, links.ErrInvalid)
			return
		}
		sms := "sms:" + v.GatewayNumber + "?body=" + url.QueryEscape(payload.Code)
		if strings.Contains(strings.ToLower(r.UserAgent()), "iphone") {
			sms = "sms:" + v.GatewayNumber + "&body=" + url.QueryEscape(payload.Code)
		}
		data.Title = "Отправить SMS — PhoneGate"
		data.Heading = "Отправьте SMS"
		data.Description = "Откроется приложение сообщений с заполненным номером и кодом. Проверьте текст и нажмите «Отправить»."
		data.Button = "Открыть приложение SMS"
		data.TargetURI = template.URL(sms)
		data.Code = payload.Code
		data.IsSMS = true
	case "call":
		if !v.IsCall() {
			s.publicError(w, r, links.ErrInvalid)
			return
		}
		data.Title = "Позвонить — PhoneGate"
		data.Heading = "Позвоните на номер шлюза"
		data.Description = "Откроется приложение телефона с заполненным номером. Позвоните и сбросьте вызов."
		data.Button = "Открыть телефон"
		data.TargetURI = template.URL("tel:" + v.GatewayNumber)
	default:
		s.publicError(w, r, links.ErrInvalid)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	if err := actionTemplate.Execute(w, data); err != nil {
		s.log.Error("public.action_render_failed", "error", err)
	}
}

func (s *Server) publicPage(w http.ResponseWriter, r *http.Request) {
	v, payload, err := s.resolvePublic(r, r.PathValue("token"))
	if err != nil {
		s.publicError(w, r, err)
		return
	}
	locale := v.Locale
	if locale != "en" {
		locale = "ru"
	}
	data := pageData{
		Locale:        locale,
		Title:         "Подтверждение номера — PhoneGate",
		Heading:       "Подтвердите номер",
		Instruction:   "Отправьте SMS с кодом на номер шлюза или позвоните и сбросьте вызов.",
		Code:          payload.Code,
		GatewayNumber: v.GatewayNumber,
		Status:        v.Status,
		StatusText:    statusText(v.Status),
		Expires:       v.ExpiresAt.UTC().Format("2006-01-02 15:04"),
		Pending:       v.Status == model.StatusPending,
		Verified:      v.Status == model.StatusVerified,
		Failed:        v.Status == model.StatusFailed,
	}
	if locale == "en" {
		data.Title = "Phone verification — PhoneGate"
		data.Heading = "Verify your phone"
		data.Instruction = "Send the SMS code to the gateway number, or call it and hang up."
		data.StatusText = statusTextEN(v.Status)
	}
	if v.IsSMS() && payload.Code != "" {
		data.SMSURI = template.URL("sms:" + v.GatewayNumber + "?body=" + url.QueryEscape(payload.Code))
		data.SMSURIIOS = template.URL("sms:" + v.GatewayNumber + "&body=" + url.QueryEscape(payload.Code))
		if strings.Contains(strings.ToLower(r.UserAgent()), "iphone") {
			data.SMSURI = data.SMSURIIOS
		}
		data.QRURL = s.svc.Links().QRURL(r.PathValue("token")) + "?kind=sms"
	}
	if v.IsCall() {
		data.TelURI = template.URL("tel:" + v.GatewayNumber)
		if data.QRURL == "" {
			data.QRURL = s.svc.Links().QRURL(r.PathValue("token")) + "?kind=call"
		}
	}
	if v.Phone != nil {
		data.PhoneMasked = phone.Mask(*v.Phone)
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	if err := publicTemplate.Execute(w, data); err != nil {
		s.log.Error("public.page_render_failed", "error", err)
	}
}

func (s *Server) publicStatus(w http.ResponseWriter, r *http.Request) {
	v, _, err := s.resolvePublic(r, r.PathValue("token"))
	if err != nil {
		s.publicError(w, r, err)
		return
	}
	body := map[string]any{
		"status":      v.Status,
		"expires_at":  v.ExpiresAt.UTC(),
		"verified_at": nil,
	}
	if v.VerifiedAt != nil {
		body["verified_at"] = v.VerifiedAt.UTC()
	}
	if v.Phone != nil {
		body["phone_masked"] = phone.Mask(*v.Phone)
	} else {
		body["phone_masked"] = nil
	}
	writeJSON(w, http.StatusOK, body)
}

func (s *Server) publicQR(w http.ResponseWriter, r *http.Request) {
	v, payload, err := s.resolvePublic(r, r.PathValue("token"))
	if err != nil {
		s.publicError(w, r, err)
		return
	}
	kind := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("kind")))
	content := s.qrContent(r.PathValue("token"), v, payload.Code, kind)
	if content == "" {
		s.publicError(w, r, links.ErrInvalid)
		return
	}
	level := qrcode.Medium
	switch s.cfg.QRErrorLevel() {
	case "low":
		level = qrcode.Low
	case "high":
		level = qrcode.High
	case "highest":
		level = qrcode.Highest
	}
	qr, err := qrcode.New(content, level)
	if err != nil {
		s.log.Error("public.qr_failed", "error", err)
		s.publicError(w, r, links.ErrInvalid)
		return
	}
	qr.DisableBorder = s.cfg.QRMargin() == 0
	png, err := qr.PNG(s.cfg.QRSize())
	if err != nil {
		s.publicError(w, r, links.ErrInvalid)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(png)
}

func (s *Server) qrContent(token string, v *model.Verification, plainCode, kind string) string {
	switch kind {
	case "sms":
		if !v.IsSMS() || plainCode == "" {
			return ""
		}
		// Encode an HTTPS landing page, not sms: directly: Android camera and
		// many QR readers only display a raw sms: URI as text.
		return s.svc.Links().ActionURL(token, "sms")
	case "call":
		if !v.IsCall() {
			return ""
		}
		return s.svc.Links().ActionURL(token, "call")
	case "":
		// Legacy behaviour: the QR of a single-method verification contains
		// the raw sms:/tel: URI. New clients should use kind=sms|call.
	default:
		return ""
	}
	if v.IsSMS() && plainCode != "" {
		return "sms:" + v.GatewayNumber + "?body=" + url.QueryEscape(plainCode)
	}
	if v.IsCall() {
		return "tel:" + v.GatewayNumber
	}
	return ""
}

func (s *Server) resolvePublic(r *http.Request, token string) (*model.Verification, *links.Payload, error) {
	payload, err := s.svc.Links().Parse(token)
	if err != nil {
		return nil, nil, err
	}
	v, err := s.svc.GetByID(r.Context(), payload.Session)
	if err != nil {
		return nil, nil, links.ErrInvalid
	}
	if v.CodeHash == nil {
		if payload.Code != "" {
			return nil, nil, links.ErrInvalid
		}
	} else if payload.Code == "" || !code.EqualHash(v.CodeHash, payload.Code) {
		return nil, nil, links.ErrInvalid
	}
	if v.Status == model.StatusExpired || v.Status == model.StatusCancelled ||
		(v.Status == model.StatusPending && !v.ExpiresAt.After(s.svc.Now())) {
		return nil, nil, links.ErrExpired
	}
	return v, payload, nil
}

func (s *Server) publicError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, links.ErrExpired):
		writeError(w, r, http.StatusGone, "link_expired", "Ссылка истекла", 0)
	case errors.Is(err, links.ErrInvalid):
		writeError(w, r, http.StatusNotFound, "not_found", "Ссылка не найдена", 0)
	default:
		writeError(w, r, http.StatusNotFound, "not_found", "Ссылка не найдена", 0)
	}
}

func statusText(status string) string {
	switch status {
	case model.StatusVerified:
		return "Номер подтверждён"
	case model.StatusFailed:
		return "Подтверждение не удалось"
	case model.StatusExpired, model.StatusCancelled:
		return "Ссылка больше не действует"
	default:
		return "Ожидает подтверждения"
	}
}

func statusTextEN(status string) string {
	switch status {
	case model.StatusVerified:
		return "Phone verified"
	case model.StatusFailed:
		return "Verification failed"
	case model.StatusExpired, model.StatusCancelled:
		return "This link is no longer valid"
	default:
		return "Waiting for confirmation"
	}
}
