// ==============================================================================
// PROYECTO YUI - CONTROLADOR PRINCIPAL DE INTERFAZ & VOZ (PWA)
// ==============================================================================

class YuiApp {
    constructor() {
        this.history = [];
        this.isSpeaking = false;
        this.recognition = null;
        this.audioPlayer = new Audio();

        this.initDOMElements();
        this.initSpeechRecognition();
        this.bindEvents();
        this.loadSystemStatus();
        this.sendInitialGreeting();
    }

    initDOMElements() {
        this.chatContainer = document.getElementById('chatMessages');
        this.userInput = document.getElementById('userInput');
        this.sendBtn = document.getElementById('sendBtn');
        this.micBtn = document.getElementById('micBtn');
        this.avatarWrapper = document.getElementById('avatarWrapper');
        this.statusBadge = document.getElementById('statusBadge');
        this.remindersCountEl = document.getElementById('remindersCount');
        
        // Modales
        this.remindersModal = document.getElementById('remindersModal');
        this.memoriesModal = document.getElementById('memoriesModal');
        this.modalBodyReminders = document.getElementById('modalBodyReminders');
        this.modalBodyMemories = document.getElementById('modalBodyMemories');
    }

    bindEvents() {
        this.sendBtn.addEventListener('click', () => this.handleSendMessage());
        this.userInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                this.handleSendMessage();
            }
        });

        this.micBtn.addEventListener('click', () => this.toggleVoiceRecognition());

        document.getElementById('btnOpenReminders').addEventListener('click', () => this.openRemindersModal());
        document.getElementById('btnOpenMemories').addEventListener('click', () => this.openMemoriesModal());
        
        document.querySelectorAll('.sao-modal-close').forEach(btn => {
            btn.addEventListener('click', () => this.closeModals());
        });

        // Cerrar modal al hacer clic en el backdrop
        document.querySelectorAll('.sao-modal-backdrop').forEach(modal => {
            modal.addEventListener('click', (e) => {
                if (e.target === modal) this.closeModals();
            });
        });
    }

    async loadSystemStatus() {
        try {
            const res = await fetch('/api/status');
            const data = await res.json();
            if (data.active_reminders_count !== undefined) {
                this.remindersCountEl.innerText = data.active_reminders_count;
            }
        } catch (e) {
            console.warn('Backend aún iniciando...', e);
        }
    }

    async sendInitialGreeting() {
        // Sonido de enlace SAO
        setTimeout(() => window.saoAudio?.playMessageChime(), 600);
    }

    // ==========================================================================
    // ENVÍO Y RECEPCIÓN DE MENSAJES
    // ==========================================================================

    async handleSendMessage() {
        const text = this.userInput.value.trim();
        if (!text) return;

        window.saoAudio?.playClick();
        this.userInput.value = '';
        this.appendMessage('user', text);

        // Estado visual de pensamiento
        this.setAvatarState('thinking');

        try {
            const res = await fetch('/api/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    message: text,
                    history: this.history
                })
            });

            const data = await res.json();
            const reply = data.reply || "...";

            this.appendMessage('assistant', reply);
            this.history.push({ role: 'user', content: text });
            this.history.push({ role: 'assistant', content: reply });

            window.saoAudio?.playMessageChime();

            // Reproducir voz
            this.speakReply(reply);
            this.loadSystemStatus();

        } catch (error) {
            console.error('Error enviando mensaje:', error);
            this.appendMessage('assistant', '🌸 Ocurrió un detalle al conectar con mi núcleo. Por favor intenta de nuevo.');
            this.setAvatarState('idle');
        }
    }

    appendMessage(role, text) {
        const msgDiv = document.createElement('div');
        msgDiv.className = `sao-msg ${role}`;

        const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        const avatarIcon = role === 'user' ? '👤' : '🌸';

        // Formatear negritas
        const formattedText = text.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');

        msgDiv.innerHTML = `
            <div class="sao-msg-avatar">${avatarIcon}</div>
            <div class="sao-msg-bubble">
                <div class="sao-msg-text">${formattedText}</div>
                <span class="sao-msg-time">${timeStr}</span>
            </div>
        `;

        this.chatContainer.appendChild(msgDiv);
        this.chatContainer.scrollTop = this.chatContainer.scrollHeight;
    }

    // ==========================================================================
    // VOZ & AUDIO
    // ==========================================================================

    async speakReply(text) {
        this.setAvatarState('speaking');

        try {
            const res = await fetch('/api/voice/speak', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text })
            });

            if (!res.ok) throw new Error('Error al sintetizar voz');

            const blob = await res.blob();
            const audioUrl = URL.createObjectURL(blob);

            this.audioPlayer.src = audioUrl;
            this.audioPlayer.onended = () => {
                this.setAvatarState('idle');
                URL.revokeObjectURL(audioUrl);
            };
            this.audioPlayer.play().catch(e => {
                console.warn('Autoplay bloqueado por navegador:', e);
                this.setAvatarState('idle');
            });

        } catch (e) {
            console.warn('Voz no disponible en cliente:', e);
            this.setAvatarState('idle');
        }
    }

    setAvatarState(state) {
        if (state === 'speaking') {
            this.avatarWrapper.classList.add('speaking');
        } else {
            this.avatarWrapper.classList.remove('speaking');
        }
    }

    // ==========================================================================
    // RECONOCIMIENTO DE VOZ (STT - MICRÓFONO)
    // ==========================================================================

    initSpeechRecognition() {
        const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (SpeechRec) {
            this.recognition = new SpeechRec();
            this.recognition.lang = 'es-MX';
            this.recognition.continuous = false;
            this.recognition.interimResults = false;

            this.recognition.onstart = () => {
                this.micBtn.classList.add('recording');
                window.saoAudio?.playClick();
            };

            this.recognition.onresult = (event) => {
                const transcript = event.results[0][0].transcript;
                this.userInput.value = transcript;
                this.handleSendMessage();
            };

            this.recognition.onerror = (event) => {
                console.warn('Error en micrófono:', event.error);
                this.micBtn.classList.remove('recording');
            };

            this.recognition.onend = () => {
                this.micBtn.classList.remove('recording');
            };
        } else {
            this.micBtn.style.display = 'none';
        }
    }

    toggleVoiceRecognition() {
        if (!this.recognition) return;
        try {
            this.recognition.start();
        } catch (e) {
            this.recognition.stop();
        }
    }

    // ==========================================================================
    // MODALES (RECORDATORIOS Y MEMORIAS)
    // ==========================================================================

    async openRemindersModal() {
        window.saoAudio?.playMenuOpen();
        this.remindersModal.classList.add('active');
        this.modalBodyReminders.innerHTML = '<p class="sao-card-text">Consultando base de datos en la nube...</p>';

        try {
            const res = await fetch('/api/reminders');
            const reminders = await res.json();
            if (reminders.length === 0) {
                this.modalBodyReminders.innerHTML = '<p class="sao-card-text" style="color: var(--text-muted);">No tienes recordatorios pendientes.</p>';
                return;
            }

            this.modalBodyReminders.innerHTML = reminders.map(r => `
                <div class="sao-card-item">
                    <div>
                        <div class="sao-card-text">📌 <strong>${r.title}</strong></div>
                        <div class="sao-card-sub">Para: ${r.due_datetime} ${r.description ? '• ' + r.description : ''}</div>
                    </div>
                </div>
            `).join('');

        } catch (e) {
            this.modalBodyReminders.innerHTML = '<p class="sao-card-text" style="color: red;">Error cargando recordatorios.</p>';
        }
    }

    async openMemoriesModal() {
        window.saoAudio?.playMenuOpen();
        this.memoriesModal.classList.add('active');
        this.modalBodyMemories.innerHTML = '<p class="sao-card-text">Consultando recuerdos en MongoDB Atlas...</p>';

        try {
            const res = await fetch('/api/memories');
            const memories = await res.json();
            if (memories.length === 0) {
                this.modalBodyMemories.innerHTML = '<p class="sao-card-text" style="color: var(--text-muted);">Aún no hay recuerdos guardados.</p>';
                return;
            }

            this.modalBodyMemories.innerHTML = memories.map(m => `
                <div class="sao-card-item">
                    <div>
                        <div class="sao-card-text">🧠 ${m.content}</div>
                    </div>
                </div>
            `).join('');

        } catch (e) {
            this.modalBodyMemories.innerHTML = '<p class="sao-card-text" style="color: red;">Error cargando recuerdos.</p>';
        }
    }

    closeModals() {
        window.saoAudio?.playClick();
        this.remindersModal.classList.remove('active');
        this.memoriesModal.classList.remove('active');
    }
}

document.addEventListener('DOMContentLoaded', () => {
    window.app = new YuiApp();
});
