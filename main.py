import requests
from flask import Flask, request, Response, stream_with_context
import json
import threading
import subprocess
import tkinter as tk
from tkinter import scrolledtext, messagebox
import os
import time

app = Flask(__name__)

# المتغير العام لرابط Ollama
ollama_target_url = "http://127.0.0.1:11434"

def log_bridge(message):
    bridge_log.insert(tk.END, f"[BRIDGE] {message}\n")
    bridge_log.see(tk.END)

def log_ollama(message):
    ollama_log.insert(tk.END, f"{message}\n")
    ollama_log.see(tk.END)

@app.route('/api/tags', methods=['GET'])
def get_models():
    try:
        response = requests.get(f"{ollama_target_url}/api/tags", timeout=5)
        return response.json()
    except:
        return {"models": []}

@app.route('/api/generate', methods=['POST'])
def proxy_ollama():
    data = request.json
    log_bridge(f"--> طلب جديد من الهاتف لموديل: {data.get('model')}")
    
    def generate():
        try:
            # استخدام الرابط المأخوذ من الواجهة
            with requests.post(f"{ollama_target_url}/api/generate", json=data, stream=True, timeout=120) as r:
                for line in r.iter_lines():
                    if line: yield line + b"\n"
        except Exception as e:
            log_bridge(f"خطأ في الاتصال بـ Ollama: {str(e)}")
            yield json.dumps({"response": "Error connecting to engine"}).encode()
            
    return Response(stream_with_context(generate()), content_type='application/json')

def start_ollama_process():
    # إعداد البيئة للسماح بالاتصال الخارجي
    new_env = os.environ.copy()
    new_env["OLLAMA_HOST"] = "0.0.0.0"
    new_env["OLLAMA_ORIGINS"] = "*"
    
    log_ollama("جاري محاولة تشغيل محرك Ollama...")
    try:
        # إغلاق أي نسخة قديمة لضمان عدم حدوث تعارض في المنفذ
        subprocess.run(["taskkill", "/f", "/im", "ollama.exe"], capture_output=True)
    except: pass

    process = subprocess.Popen(
        ["ollama", "serve"], 
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, 
        text=True, bufsize=1, env=new_env
    )
    for line in iter(process.stdout.readline, ""):
        log_ollama(line.strip())

def run_flask(port):
    try:
        log_bridge(f"جاري تشغيل الجسر على المنفذ: {port}")
        # التشغيل على 0.0.0.0 ليكون متاحاً للهاتف عبر الواي فاي
        app.run(host='0.0.0.0', port=int(port), threaded=True, use_reloader=False)
    except Exception as e:
        messagebox.showerror("خطأ", f"فشل تشغيل الجسر على منفذ {port}: {e}")

def start_all():
    global ollama_target_url
    
    # جلب القيم من واجهة البرنامج وتصحيح الفراغات
    ollama_target_url = ollama_entry.get().strip()
    bridge_port = port_entry.get().strip()
    
    if not bridge_port:
        messagebox.showwarning("تنبيه", "يرجى إدخال رقم منفذ الجسر (مثلاً 5005)")
        return

    start_button.config(state=tk.DISABLED, text="SYSTEM ONLINE", bg="#27ae60")
    
    # تشغيل المحرك أولاً ثم الجسر
    threading.Thread(target=start_ollama_process, daemon=True).start()
    time.sleep(2) # انتظار بسيط لتهيئة Ollama
    threading.Thread(target=run_flask, args=(bridge_port,), daemon=True).start()

# --- إعداد الواجهة الرسومية ---
root = tk.Tk()
root.title("YASSER AI - Master Controller V2")
root.geometry("1000x600") # تم إصلاح الخطأ هنا
root.configure(bg="#000000")

# العنوان
tk.Label(root, text="YASSER AI CONTROL CENTER", fg="#E50914", bg="#000000", font=("Arial", 20, "bold")).pack(pady=10)

# لوحة الإعدادات
settings_frame = tk.Frame(root, bg="#1a1a1a", padx=15, pady=15)
settings_frame.pack(fill=tk.X, padx=20)

# حقل Ollama URL
tk.Label(settings_frame, text="Ollama URL:", fg="white", bg="#1a1a1a", font=("Arial", 10)).grid(row=0, column=0, padx=5)
ollama_entry = tk.Entry(settings_frame, width=35, font=("Consolas", 10))
ollama_entry.insert(0, "http://127.0.0.1:11434")
ollama_entry.grid(row=0, column=1, padx=10)

# حقل Bridge Port
tk.Label(settings_frame, text="Bridge Port:", fg="white", bg="#1a1a1a", font=("Arial", 10)).grid(row=0, column=2, padx=5)
port_entry = tk.Entry(settings_frame, width=12, font=("Consolas", 10))
port_entry.insert(0, "5005")
port_entry.grid(row=0, column=3, padx=10)

# زر التشغيل
start_button = tk.Button(settings_frame, text="START ENGINES", command=start_all, bg="#E50914", fg="white", font=("Arial", 10, "bold"), padx=25)
start_button.grid(row=0, column=4, padx=20)

# منطقة السجلات
terminal_frame = tk.Frame(root, bg="#000000")
terminal_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)

# سجل الجسر (يسار)
left_frame = tk.Frame(terminal_frame, bg="#000000")
left_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
tk.Label(left_frame, text="BRIDGE LOG (Port 5005)", fg="#00FF00", bg="#000000", font=("Arial", 10, "bold")).pack()
bridge_log = scrolledtext.ScrolledText(left_frame, bg="#000000", fg="#00FF00", font=("Consolas", 9), bd=1, relief=tk.SOLID)
bridge_log.pack(fill=tk.BOTH, expand=True)

# سجل Ollama (يمين)
right_frame = tk.Frame(terminal_frame, bg="#000000")
right_frame.pack(side=tk.RIGHT, fill=tk.BOTH, expand=True, padx=(10,0))
tk.Label(right_frame, text="OLLAMA ENGINE LOG", fg="#00FFFF", bg="#000000", font=("Arial", 10, "bold")).pack()
ollama_log = scrolledtext.ScrolledText(right_frame, bg="#000000", fg="#00FFFF", font=("Consolas", 9), bd=1, relief=tk.SOLID)
ollama_log.pack(fill=tk.BOTH, expand=True)

root.mainloop()