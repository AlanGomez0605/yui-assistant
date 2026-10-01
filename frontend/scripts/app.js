// ==============================================================================
// PROYECTO YUI - CONTROLADOR PRINCIPAL DEL CHAT (TEXTO, VOZ Y RECUERDOS)
// ==============================================================================

class YuiApp {
    constructor() {
        this.history = [];
        this.recognition = null;
        this.audioPlayer = new Audio();

        this.initDOMElements();
        this.initSpeechRecognition();
        this.initServiceWorker();
        this.initConnectionMonitor();
        this.bindEvents();
        this.initAuthentication();
        this.sendInitialGreeting();
    }

    initDOMElements() {
        this.chatContainer = document.getElementById('chatMessages');
        this.userInput = document.getElementById('userInput');
        this.sendBtn = document.getElementById('sendBtn');
        this.micBtn = document.getElementById('micBtn');
        this.avatarWrapper = document.getElementById('avatarWrapper');
        this.statusBadge = document.getElementById('statusBadge');
        this.connectionHint = document.getElementById('connectionHint');

        this.memoriesModal = document.getElementById('memoriesModal');
        this.modalBodyMemories = document.getElementById('modalBodyMemories');

        this.authModal = document.getElementById('authModal');
        this.authTokenInput = document.getElementById('authTokenInput');
        this.authSubmitBtn = document.getElementById('authSubmitBtn');
        this.authError = document.getElementById('authError');
    }

    initServiceWorker() {
        if ('serviceWorker' in navigator) {
            window.addEventListener('load', () => {
                navigator.serviceWorker.register('/sw.js').then(
                    (reg) => console.log('PWA ServiceWorker registrado con éxito:', reg.scope),
                    (err) => console.warn('Fallo al registrar ServiceWorker:', err)
                );
            });
        }
    }

    // ==========================================================================
    // ESTADO DE CONEXIÓN (EL CHAT REQUIERE INTERNET)
    // ==========================================================================

    initConnectionMonitor() {
        window.addEventListener('online', () => this.updateConnectionState());
        window.addEventListener('offline', () => this.updateConnectionState());
        this.updateConnectionState();
    }

    updateConnectionState() {
        const online = navigator.onLine;
        if (this.statusBadge) {
            this.statusBadge.classList.toggle('online', online);
            this.statusBadge.innerText = online ? 'ONLINE' : 'SIN CONEXIÓN';
        }
        if (this.connectionHint) {
            this.connectionHint.textContent = online ? '' : 'Sin internet: Yui no puede responder';
        }
    }

    async initAuthentication() {
        try {
            const response = await fetch('/api/auth/status', { credentials: 'same-origin' });
            const status = await response.json();
            if (status.authenticated) {
                this.authModal?.classList.remove('active');
                return;
            }
            this.showAuthModal(
                status.configured ? '' : 'El servidor debe configurar API_TOKEN antes de aceptar conexiones.'
            );
        } catch (error) {
            if (!navigator.onLine) return;  // Sin internet: el aviso de conexión ya lo indica
            this.showAuthModal('No se pudo comprobar la seguridad del servidor.');
        }
    }

    showAuthModal(message = '') {
        this.authModal?.classList.add('active');
        if (this.authError) this.authError.textContent = message;
        setTimeout(() => this.authTokenInput?.focus(), 0);
    }

    async submitAuthentication() {
        const token = this.authTokenInput?.value.trim() || '';
        if (!token) {
            this.showAuthModal('Introduce el token de acceso.');
            return;
        }
        this.authSubmitBtn.disabled = true;
        try {
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token })
            });
            const data = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(data.detail || 'No fue posible iniciar sesion.');
            this.authTokenInput.value = '';
            this.authModal?.classList.remove('active');
        } catch (error) {
            this.showAuthModal(error.message);
        } finally {
            this.authSubmitBtn.disabled = false;
        }
    }

    async apiFetch(url, options = {}) {
        const response = await fetch(url, { ...options, credentials: 'same-origin' });
        if (response.status === 401) {
            this.showAuthModal('La sesion expiro. Introduce nuevamente el token.');
            throw new Error('Authentication required');
        }
        if (!response.ok) {
            const data = await response.json().catch(() => ({}));
            throw new Error(data.detail || ('HTTP ' + response.status));
        }
        return response;
    }

    // ==========================================================================
    // DETECCIÓN DE HORA Y ZONA HORARIA LOCAL DEL CLIENTE
    // ==========================================================================

    getClientTimePayload() {
        const now = new Date();
        const year = now.getFullYear();
        const month = String(now.getMonth() + 1).padStart(2, '0');
        const day = String(now.getDate()).padStart(2, '0');
        const hours = String(now.getHours()).padStart(2, '0');
        const minutes = String(now.getMinutes()).padStart(2, '0');
        const seconds = String(now.getSeconds()).padStart(2, '0');

        return {
            client_time: `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`,
            client_timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Mexico_City'
        };
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

        document.getElementById('btnOpenMemories').addEventListener('click', () => this.openMemoriesModal());

        this.authSubmitBtn?.addEventListener('click', () => this.submitAuthentication());
        this.authTokenInput?.addEventListener('keydown', (event) => {
            if (event.key === 'Enter') this.submitAuthentication();
        });

        document.querySelectorAll('.sao-modal-close').forEach(btn => {
            btn.addEventListener('click', () => this.closeModals());
        });

        // Cerrar modal al hacer clic en el backdrop
        document.querySelectorAll('.sao-modal-backdrop').forEach(modal => {
            if (modal === this.authModal) return;
            modal.addEventListener('click', (e) => {
                if (e.target === modal) this.closeModals();
            });
        });
    }

    async sendInitialGreeting() {
        // Sonido de enlace SAO
        setTimeout(() => window.saoAudio?.playMessageChime(), 600);
    }

    // ==========================================================================
    // ENVÍO Y RECEPCIÓN DE MENSAJES CON HORA LOCAL
    // ==========================================================================

    async handleSendMessage() {
        const text = this.userInput.value.trim();
        if (!text) return;

        // Sin internet el texto se queda en la caja para enviarlo después
        if (!navigator.onLine) {
            this.updateConnectionState();
            this.appendMessage('assistant', '🌸 Ahora no tienes internet, así que no puedo responderte. Tu mensaje sigue en la caja de texto para enviarlo cuando vuelva la conexión.');
            return;
        }

        window.saoAudio?.playClick();
        this.userInput.value = '';
        this.appendMessage('user', text);
        this.setAvatarState('thinking');

        const timeData = this.getClientTimePayload();

        try {
            const res = await this.apiFetch('/api/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    message: text,
                    history: this.history.slice(-40),
                    client_time: timeData.client_time,
                    client_timezone: timeData.client_timezone
                })
            });

            const data = await res.json();
            const reply = data.reply || "...";

            this.appendMessage('assistant', reply);
            this.history.push({ role: 'user', content: text });
            this.history.push({ role: 'assistant', content: reply });
            if (this.history.length > 40) this.history = this.history.slice(-40);

            window.saoAudio?.playMessageChime();
            this.speakReply(reply);

        } catch (error) {
            console.error('Error enviando mensaje:', error);
            if (error instanceof TypeError) {
                // Fallo de red: devolver el texto a la caja para reintentar
                if (!this.userInput.value) this.userInput.value = text;
                this.updateConnectionState();
                this.appendMessage('assistant', '🌸 Se perdió la conexión con mi núcleo. Dejé tu mensaje en la caja para que lo reenvíes.');
            } else {
                this.appendMessage('assistant', '🌸 Ocurrió un detalle al conectar con mi núcleo. Por favor intenta de nuevo.');
            }
            this.setAvatarState('idle');
        }
    }

    appendMessage(role, text) {
        const msgDiv = document.createElement('div');
        msgDiv.className = `sao-msg ${role}`;

        const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        const avatarIcon = role === 'user' ? '👤' : '🌸';

        // Formatear negritas
        const formattedText = this.escapeHtml(text).replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');

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

    escapeHtml(text) {
        return String(text ?? '').replace(/[&<>"']/g, (char) => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
        })[char]);
    }

    // ==========================================================================
    // VOZ & AUDIO
    // ==========================================================================

    async speakReply(text) {
        this.setAvatarState('speaking');

        try {
            const res = await this.apiFetch('/api/voice/speak', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text })
            });

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
    // RECUERDOS
    // ==========================================================================

    async openMemoriesModal() {
        window.saoAudio?.playMenuOpen();
        this.memoriesModal.classList.add('active');
        this.modalBodyMemories.innerHTML = '<p class="sao-card-text">Consultando recuerdos en MongoDB Atlas...</p>';

        try {
            const res = await this.apiFetch('/api/memories');
            const memories = await res.json();
            if (memories.length === 0) {
                this.modalBodyMemories.innerHTML = '<p class="sao-card-text" style="color: var(--text-muted);">Aún no hay recuerdos guardados.</p>';
                return;
            }

            this.modalBodyMemories.innerHTML = memories.map(m => `
                <div class="sao-card-item" style="display: flex; justify-content: space-between; align-items: center;">
                    <div style="flex: 1;">
                        <div class="sao-card-text">🧠 ${this.escapeHtml(m.content)}</div>
                    </div>
                    <button class="btn-del-memory" data-id="${this.escapeHtml(m.id)}" style="background: transparent; border: none; color: var(--accent-pink); font-size: 1.1rem; cursor: pointer; padding: 0.3rem;" title="Eliminar recuerdo de la BD">
                        🗑️
                    </button>
                </div>
            `).join('');

            document.querySelectorAll('.btn-del-memory').forEach(btn => {
                btn.addEventListener('click', async () => {
                    const id = btn.getAttribute('data-id');
                    await this.apiFetch(`/api/memories/${encodeURIComponent(id)}`, { method: 'DELETE' });
                    this.openMemoriesModal();
                });
            });

        } catch (e) {
            const msg = navigator.onLine ? 'Error cargando recuerdos.' : 'Sin internet: no se pueden cargar los recuerdos.';
            this.modalBodyMemories.innerHTML = `<p class="sao-card-text" style="color: red;">${msg}</p>`;
        }
    }

    closeModals() {
        window.saoAudio?.playClick();
        this.memoriesModal.classList.remove('active');
    }
}

document.addEventListener('DOMContentLoaded', () => {
    window.app = new YuiApp();
});
