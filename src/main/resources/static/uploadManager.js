class UploadItem {
    constructor(file, clientId) {
        this.clientId = clientId;
        this.file = file;
        this.fileName = file.name;
        this.size = file.size;
        this.contentType = file.type;
        this.checksum = null;
        this.folderId = currentFolderId;
        this.status = 'HASHING'; // HASHING, PREFLIGHT, WAITING_DECISION, APPROVED, CANCELLED, UPLOADING, PAUSED, COMPLETED, FAILED
        this.conflictType = null; // BATCH_CONFLICT, SERVER_CONFLICT
        this.duplicateAction = 'NONE'; // NONE, KEEP_BOTH, REPLACE
        this.progress = 0;
        this.uploadedChunks = [];
        this.sessionId = null;
        this.abortController = null;
        this.errorMsg = null;
    }
}

class UploadManager {
    constructor() {
        this.items = new Map();
        this.activeUploads = 0;
        this.maxConcurrent = 5;
        this.CHUNK_SIZE = 20 * 1024 * 1024;
        
        this.panel = document.getElementById('floatingUploadPanel');
        this.panelBody = document.getElementById('uploadPanelBody');
        this.btnUploadText = document.getElementById('btnUploadText');
        
        this.batchConflicts = [];
    }
    
    togglePanel() {
        if (this.panel.style.display === 'none' || this.panel.style.display === '') {
            this.panel.style.display = 'flex';
        } else {
            this.panel.style.display = 'none';
        }
    }
    
    closePanel() {
        this.panel.style.display = 'none';
    }
    
    async handleFilesSelection(fileList) {
        if (fileList.length === 0) return;
        
        // Show panel
        this.panel.style.display = 'flex';
        
        const newItems = [];
        for (let i = 0; i < fileList.length; i++) {
            const file = fileList[i];
            const clientId = 'up_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
            const item = new UploadItem(file, clientId);
            this.items.set(clientId, item);
            newItems.push(item);
        }
        
        this.renderQueue();
        
        // Phase 1: Hashing
        const hashPromises = newItems.map(item => this.hashItem(item));
        await Promise.all(hashPromises);
        
        // Detect Batch Conflicts
        const checksumMap = new Map();
        for (const item of newItems) {
            if (item.status === 'FAILED') continue;
            if (!checksumMap.has(item.checksum)) {
                checksumMap.set(item.checksum, []);
            }
            checksumMap.get(item.checksum).push(item);
        }
        
        for (const [checksum, itemsWithHash] of checksumMap.entries()) {
            if (itemsWithHash.length > 1) {
                // First one is fine, others are batch conflicts
                for (let i = 1; i < itemsWithHash.length; i++) {
                    itemsWithHash[i].conflictType = 'BATCH_CONFLICT';
                    itemsWithHash[i].status = 'WAITING_DECISION';
                }
            }
        }
        
        // Phase 2: Preflight for non-batch conflicts
        const preflightRequests = newItems
            .filter(item => item.status === 'PREFLIGHT' && !item.conflictType)
            .map(item => ({
                clientId: item.clientId,
                fileName: item.fileName,
                size: item.size,
                contentType: item.contentType,
                checksum: item.checksum,
                folderId: item.folderId
            }));
            
        if (preflightRequests.length > 0) {
            try {
                const res = await fetch('/api/files/preflight', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(preflightRequests)
                });
                
                if (res.ok) {
                    const responses = await res.json();
                    responses.forEach(resp => {
                        const item = this.items.get(resp.clientId);
                        if (item) {
                            if (resp.status === 'SERVER_CONFLICT') {
                                item.conflictType = 'SERVER_CONFLICT';
                                item.status = 'WAITING_DECISION';
                            } else {
                                item.status = 'APPROVED';
                            }
                        }
                    });
                } else {
                    console.error("Preflight failed");
                    newItems.forEach(item => { if(item.status === 'PREFLIGHT') item.status = 'FAILED'; });
                }
            } catch (e) {
                console.error("Preflight request failed", e);
                newItems.forEach(item => { if(item.status === 'PREFLIGHT') item.status = 'FAILED'; });
            }
        }
        
        this.renderQueue();
        
        // Phase 3: Conflict UI
        this.batchConflicts = newItems.filter(item => item.status === 'WAITING_DECISION');
        if (this.batchConflicts.length > 0) {
            this.showBatchConflictModal();
        } else {
            this.processQueue();
        }
    }
    
    hashItem(item) {
        return new Promise((resolve) => {
            const worker = new Worker('/hashWorker.js');
            worker.onmessage = (e) => {
                if (e.data.success) {
                    item.checksum = e.data.hash;
                    item.status = 'PREFLIGHT';
                } else {
                    item.status = 'FAILED';
                    item.errorMsg = 'Hashing failed';
                }
                this.renderQueue();
                worker.terminate();
                resolve();
            };
            worker.onerror = () => {
                item.status = 'FAILED';
                item.errorMsg = 'Worker error';
                this.renderQueue();
                worker.terminate();
                resolve();
            };
            worker.postMessage(item.file);
        });
    }
    
    showBatchConflictModal() {
        const listDiv = document.getElementById('conflictList');
        listDiv.innerHTML = '';
        
        this.batchConflicts.forEach(item => {
            const div = document.createElement('div');
            div.style = "border: 1px solid var(--border-color); padding: 12px; border-radius: 8px;";
            
            const desc = item.conflictType === 'BATCH_CONFLICT' 
                ? 'Duplicate within selected files.' 
                : 'Already exists in this folder.';
                
            div.innerHTML = `
                <div style="font-weight: bold; margin-bottom: 4px;">${item.fileName}</div>
                <div style="font-size: 13px; color: var(--danger-color); margin-bottom: 12px;">${desc}</div>
                <div style="display: flex; gap: 8px;">
                    <select id="decision_${item.clientId}" class="modal-input" style="padding: 6px; flex: 1; margin: 0;">
                        <option value="NONE">Select Action...</option>
                        <option value="KEEP_BOTH">Keep Both</option>
                        <option value="REPLACE">Replace Existing</option>
                        <option value="CANCEL">Don't Upload</option>
                    </select>
                </div>
            `;
            listDiv.appendChild(div);
        });
        
        showModal('batchConflictModal');
    }
    
    confirmBatchDecisions() {
        let allDecided = true;
        this.batchConflicts.forEach(item => {
            const decision = document.getElementById(`decision_${item.clientId}`).value;
            if (decision === 'NONE') {
                allDecided = false;
            }
        });
        
        if (!allDecided) {
            alert('Please select an action for all conflicting files.');
            return;
        }
        
        this.batchConflicts.forEach(item => {
            const decision = document.getElementById(`decision_${item.clientId}`).value;
            if (decision === 'CANCEL') {
                item.status = 'CANCELLED';
            } else {
                item.duplicateAction = decision;
                item.status = 'APPROVED';
            }
        });
        
        closeModal('batchConflictModal');
        this.batchConflicts = [];
        this.renderQueue();
        this.processQueue();
    }
    
    cancelBatchUpload() {
        this.batchConflicts.forEach(item => {
            item.status = 'CANCELLED';
        });
        closeModal('batchConflictModal');
        this.batchConflicts = [];
        this.renderQueue();
        this.processQueue();
    }
    
    processQueue() {
        if (this.activeUploads >= this.maxConcurrent) return;
        
        const waitingItems = Array.from(this.items.values()).filter(item => item.status === 'APPROVED');
        
        for (const item of waitingItems) {
            if (this.activeUploads >= this.maxConcurrent) break;
            this.startUpload(item);
        }
    }
    
    async startUpload(item) {
        item.status = 'UPLOADING';
        this.activeUploads++;
        this.renderQueue();
        
        const storageKey = `upload_${item.fileName}_${item.size}`;
        let sessionId = localStorage.getItem(storageKey);
        if (sessionId === "undefined" || sessionId === "null") sessionId = null;
        
        try {
            if (!sessionId) {
                const totalChunks = Math.ceil(item.size / this.CHUNK_SIZE);
                let initUrl = `/api/files/upload/session?originalName=${encodeURIComponent(item.fileName)}&contentType=${encodeURIComponent(item.contentType)}&totalSize=${item.size}&totalChunks=${totalChunks}`;
                if (item.folderId) initUrl += `&folderId=${item.folderId}`;
                
                const initRes = await fetch(initUrl, { method: "POST" });
                if (!initRes.ok) throw new Error("Failed to init");
                const data = await initRes.json();
                sessionId = data.id;
                localStorage.setItem(storageKey, sessionId);
                item.sessionId = sessionId;
            } else {
                item.sessionId = sessionId;
                const statusRes = await fetch(`/api/files/upload/session/${sessionId}/status`);
                if (statusRes.ok) {
                    const data = await statusRes.json();
                    item.uploadedChunks = data.uploadedChunks || [];
                }
            }
            
            await this.uploadChunks(item);
            
        } catch (e) {
            if (item.status !== 'PAUSED' && item.status !== 'CANCELLED') {
                item.status = 'FAILED';
                item.errorMsg = e.message;
            }
            this.activeUploads--;
            this.renderQueue();
            this.processQueue();
        }
    }
    
    async uploadChunks(item) {
        const totalChunks = Math.ceil(item.size / this.CHUNK_SIZE);
        
        for (let i = 0; i < totalChunks; i++) {
            if (item.status !== 'UPLOADING') {
                return; // PAUSED or CANCELLED
            }
            
            item.progress = Math.round((item.uploadedChunks.length / totalChunks) * 100);
            this.renderQueue();
            
            if (item.uploadedChunks.includes(i)) continue;
            
            const start = i * this.CHUNK_SIZE;
            const end = Math.min(start + this.CHUNK_SIZE, item.size);
            const chunk = item.file.slice(start, end);
            
            const formData = new FormData();
            formData.append("file", chunk);
            formData.append("chunkIndex", i);
            
            item.abortController = new AbortController();
            
            try {
                const chunkRes = await fetch(`/api/files/upload/session/${item.sessionId}/part/${i}`, {
                    method: "PUT",
                    body: formData,
                    signal: item.abortController.signal
                });
                if (!chunkRes.ok) throw new Error("Chunk failed");
                item.uploadedChunks.push(i);
            } catch (e) {
                if (e.name === 'AbortError') return;
                throw e;
            }
        }
        
        if (item.status === 'UPLOADING') {
            await this.completeUpload(item);
        }
    }
    
    async completeUpload(item) {
        try {
            const res = await fetch(`/api/files/upload/session/${item.sessionId}/complete?duplicateAction=${item.duplicateAction}`, {
                method: "POST"
            });
            
            if (res.ok) {
                item.status = 'COMPLETED';
                item.progress = 100;
                localStorage.removeItem(`upload_${item.fileName}_${item.size}`);
                if (currentView === 'files' || currentView === 'recent') fetchFiles();
                if (currentView === 'recent') fetchRecentFiles();
            } else {
                throw new Error("Complete failed");
            }
        } catch (e) {
            item.status = 'FAILED';
            item.errorMsg = "Finalization failed";
        }
        
        this.activeUploads--;
        this.renderQueue();
        this.processQueue();
    }
    
    pauseUpload(clientId) {
        const item = this.items.get(clientId);
        if (item && item.status === 'UPLOADING') {
            item.status = 'PAUSED';
            if (item.abortController) {
                item.abortController.abort();
            }
            this.activeUploads--;
            this.renderQueue();
            this.processQueue();
        }
    }
    
    resumeUpload(clientId) {
        const item = this.items.get(clientId);
        if (item && item.status === 'PAUSED') {
            item.status = 'APPROVED';
            this.renderQueue();
            this.processQueue();
        }
    }
    
    cancelUpload(clientId) {
        const item = this.items.get(clientId);
        if (item) {
            if (item.status === 'UPLOADING' && item.abortController) {
                item.abortController.abort();
                this.activeUploads--;
            }
            item.status = 'CANCELLED';
            localStorage.removeItem(`upload_${item.fileName}_${item.size}`);
            this.renderQueue();
            this.processQueue();
        }
    }
    
    renderQueue() {
        const itemsArr = Array.from(this.items.values());
        
        // Update header summary
        const completed = itemsArr.filter(i => i.status === 'COMPLETED').length;
        const total = itemsArr.filter(i => i.status !== 'CANCELLED').length;
        
        if (total > 0) {
            this.btnUploadText.innerText = `${completed} / ${total} Done`;
        } else {
            this.btnUploadText.innerText = `Upload Files`;
        }
        
        // Render Panel
        this.panelBody.innerHTML = '';
        
        // Sorting: Uploading first, then waitings, then others
        const sorted = itemsArr.sort((a, b) => {
            const wA = a.status === 'UPLOADING' ? 0 : 1;
            const wB = b.status === 'UPLOADING' ? 0 : 1;
            return wA - wB;
        });
        
        sorted.forEach(item => {
            const div = document.createElement('div');
            div.style = "padding: 10px; background: var(--bg-color); border: 1px solid var(--border-color); border-radius: 8px; font-size: 13px; position: relative; overflow: hidden;";
            
            let color = "var(--primary-color)";
            let statusText = item.status;
            
            if (item.status === 'COMPLETED') color = "var(--success-color)";
            if (item.status === 'FAILED') color = "var(--danger-color)";
            if (item.status === 'PAUSED') color = "var(--warning-color)";
            if (item.status === 'CANCELLED') color = "var(--text-muted)";
            if (item.status === 'HASHING' || item.status === 'PREFLIGHT') statusText = 'Preparing...';
            if (item.status === 'WAITING_DECISION') statusText = 'Needs Attention';
            if (item.status === 'APPROVED') statusText = 'Waiting';
            
            let actions = '';
            if (item.status === 'UPLOADING') {
                actions = `<button class="btn-icon" style="padding: 2px;" onclick="uploadManager.pauseUpload('${item.clientId}')"><svg style="width: 16px; height: 16px;" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 9v6m4-6v6m7-3a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg></button>`;
            } else if (item.status === 'PAUSED') {
                actions = `<button class="btn-icon" style="padding: 2px;" onclick="uploadManager.resumeUpload('${item.clientId}')"><svg style="width: 16px; height: 16px;" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z"></path></svg></button>`;
            }
            
            if (item.status !== 'COMPLETED' && item.status !== 'CANCELLED') {
                actions += `<button class="btn-icon danger" style="padding: 2px;" onclick="uploadManager.cancelUpload('${item.clientId}')"><svg style="width: 16px; height: 16px;" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg></button>`;
            }
            
            div.innerHTML = `
                <div style="position: absolute; left: 0; top: 0; bottom: 0; width: ${item.progress}%; background: ${color}; opacity: 0.1;"></div>
                <div style="position: relative; z-index: 1; display: flex; justify-content: space-between; align-items: center;">
                    <div style="flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; padding-right: 8px;">
                        <strong style="color: var(--text-main);">${item.fileName}</strong><br/>
                        <span style="color: ${color};">${statusText} ${item.status === 'UPLOADING' ? item.progress + '%' : ''}</span>
                    </div>
                    <div style="display: flex; gap: 4px;">
                        ${actions}
                    </div>
                </div>
            `;
            this.panelBody.appendChild(div);
        });
    }
}

const uploadManager = new UploadManager();

function toggleUploadPanel() { uploadManager.togglePanel(); }
function closeUploadPanel() { uploadManager.closePanel(); }
function confirmBatchDecisions() { uploadManager.confirmBatchDecisions(); }
function cancelBatchUpload() { uploadManager.cancelBatchUpload(); }
