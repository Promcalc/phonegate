// Тестовый сервер для проверки обмена с Phonegate.
//
// Запускает два адреса из договора (docs/api/openapi.yaml), печатает полученные отчёты
// и, если указать ключ SIM и номер получателя, возвращает команду «отправить SMS»
// и «позвонить». Нужен, чтобы проверить приложение без реального сервера.
//
// Запуск:
//
//	go run main.go -sim "+79001234567" -sms-to "+79007654321" -call-to "+79007654321"
//
// Остановка — Ctrl+C.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"io"
	"log"
	"net/http"
	"time"
)

var (
	addr       = flag.String("addr", ":8080", "адрес и порт")
	simKey     = flag.String("sim", "", "ключ SIM в ответе (номер телефона); пусто — отвечаем пустым JSON")
	smsTo      = flag.String("sms-to", "", "вернуть команду «отправить SMS» на этот номер (через 5 секунд)")
	callTo     = flag.String("call-to", "", "вернуть команду «позвонить» на этот номер (через 10 секунд)")
	answerWait = flag.Int("answer-after", 10, "секунд до снятия трубки, как настроено в приложении")
)

const timeLayout = "2006-01-02T15:04:05-07:00"

func main() {
	flag.Parse()

	http.HandleFunc("/exchange", handleExchange)
	http.HandleFunc("/outgoing-report", handleOutgoingReport)

	log.Printf("слушаю %s", *addr)
	log.Printf("URL обмена: http://<адрес-этого-компьютера>%s/exchange", *addr)
	log.Printf("URL отчёта об исходящих: http://<адрес-этого-компьютера>%s/outgoing-report", *addr)
	if err := http.ListenAndServe(*addr, nil); err != nil {
		log.Fatal(err)
	}
}

func handleExchange(w http.ResponseWriter, r *http.Request) {
	logRequest("обмен", r)
	w.Header().Set("Content-Type", "application/json")

	if *simKey == "" {
		// Пустой объект — допустимый ответ: команд нет.
		_, _ = io.WriteString(w, "{}")
		return
	}

	sim := map[string]any{}
	if *smsTo != "" {
		sim["sms"] = []map[string]any{{
			"time":  time.Now().Add(5 * time.Second).Format(timeLayout),
			"text":  "Тестовое сообщение от Phonegate",
			"phone": *smsTo,
		}}
	}
	if *callTo != "" {
		sim["call"] = []map[string]any{{
			"time":  time.Now().Add(10 * time.Second).Format(timeLayout),
			"text":  "",
			"phone": *callTo,
		}}
	}

	response := map[string]any{*simKey: sim}
	encoded, _ := json.MarshalIndent(response, "", "  ")
	log.Printf("ответ с командами (снятие трубки настроено на %d с):\n%s", *answerWait, encoded)
	_, _ = w.Write(encoded)
}

func handleOutgoingReport(w http.ResponseWriter, r *http.Request) {
	// Тело ответа на этот адрес приложение игнорирует.
	logRequest("отчёт об исходящих", r)
	w.WriteHeader(http.StatusNoContent)
}

func logRequest(name string, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	var pretty bytes.Buffer
	if err := json.Indent(&pretty, body, "", "  "); err != nil {
		pretty.Write(body)
	}

	authorization := "(без заголовка)"
	if value := r.Header.Get("Authorization"); value != "" {
		authorization = value
	}

	log.Printf("=== %s: %s\n%s\nтело:\n%s", name, r.URL.Path, authorization, pretty.String())
}
