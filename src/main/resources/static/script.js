let currentFolderId = null;
let pendingDuplicateFile = null;
let currentView = 'files'; // 'files' or 'trash'

// Elements
const filesContainer = document.getElementById('filesContainer');
const breadcrumb = document.getElementById('breadcrumb');
const uploadZone = document.getElementById('uploadZone');
const filesView = document.getElementById('filesView');
const trashView = document.getElementById('trashView');
const uploadForm = document.getElementById('uploadForm');

// Navigation
function switchView(view) {
    currentView = view;
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    
    if (view === 'files') {
        document.getElementById('nav-files').classList.add('active');
        filesView.style.display = 'block';
        trashView.style.display = 'none';
        fetchFiles();
    } else if (view === 'trash') {
        document.getElementById('nav-trash').classList.add('active');
        filesView.style.display = 'none';
        trashView.style.display = 'block';
        fetchTrashedFiles();
    }
}

async function navigateTo(folderId) {
    currentFolderId = folderId;
    fetchFiles();
}

// Icons (SVG)
const iconFolder = `<svg fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"></path></svg>`;
const iconFile = `<svg fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z"></path></svg>`;
const iconTrash = `<svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path></svg>`;
const iconDownload = `<svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path></svg>`;
const iconEdit = `<svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"></path></svg>`;
const iconRestore = `<svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 10h10a8 8 0 018 8v2M3 10l6 6m-6-6l6-6"></path></svg>`;

async function fetchFiles() {
    try {
        let breadcrumbUrl = "/api/folders/breadcrumbs";
        if (currentFolderId) breadcrumbUrl += `?folderId=${currentFolderId}`;
        
        const pathRes = await fetch(breadcrumbUrl);
        let breadcrumbHtml = "";
        
        if (pathRes.ok) {
            const breadcrumbs = await pathRes.json();
            const svgSeparator = ` <svg style="width:16px;height:16px;opacity:0.5;margin-top:2px;" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"></path></svg> `;
            
            breadcrumbHtml = breadcrumbs.map(b => 
                `<span onclick="navigateTo(${b.id})" style="cursor:pointer; color:var(--primary-color);">${b.name}</span>`
            ).join(svgSeparator);
        } else {
            breadcrumbHtml = `<span onclick="navigateTo(null)" style="cursor:pointer; color:var(--primary-color);">Home</span>`;
        }
        
        breadcrumb.innerHTML = breadcrumbHtml;

        let folderUrl = "/api/folders";
        if (currentFolderId) folderUrl += `?parentId=${currentFolderId}`;
        const folderRes = await fetch(folderUrl);
        const folders = await folderRes.json();

        let fileUrl = "/api/files/list";
        if (currentFolderId) fileUrl += `?folderId=${currentFolderId}`;
        const fileRes = await fetch(fileUrl);
        const files = await fileRes.json();

        filesContainer.innerHTML = "";

        if (folders.length === 0 && files.length === 0) {
            filesContainer.innerHTML = `
                <div class="empty-state">
                    ${iconFolder}
                    <p>This folder is empty</p>
                </div>
            `;
            return;
        }

        // Render Folders
        folders.forEach(folder => {
            const row = document.createElement("div");
            row.className = "file-row";
            
            row.innerHTML = `
                <div class="file-info" onclick="navigateTo(${folder.id})">
                    <div class="file-icon folder-icon">${iconFolder}</div>
                    <div class="file-details">
                        <span class="file-name">${folder.name}</span>
                        <span class="file-meta">Folder</span>
                    </div>
                </div>
                <div class="file-actions">
                    <button class="btn-icon" onclick="showRenameFolderModal(${folder.id}, '${folder.name}')" title="Rename">${iconEdit}</button>
                    <button class="btn-icon danger" onclick="deleteFolder(${folder.id}, '${folder.name}')" title="Trash">${iconTrash}</button>
                </div>
            `;
            filesContainer.appendChild(row);
        });

        // Render Files
        files.forEach(file => {
            const row = document.createElement("div");
            row.className = "file-row";
            
            row.innerHTML = `
                <div class="file-info">
                    <div class="file-icon">${iconFile}</div>
                    <div class="file-details">
                        <span class="file-name" title="${file.originalName}">${file.originalName}</span>
                        <span class="file-meta">${(file.size / 1024).toFixed(2)} KB • ${new Date(file.uploadedAt).toLocaleDateString()}</span>
                    </div>
                </div>
                <div class="file-actions">
                    <a href="/api/files/download/${encodeURIComponent(file.storedName)}" download="${file.originalName}" class="btn-icon" title="Download">${iconDownload}</a>
                    <button class="btn-icon danger" onclick="deleteFile('${encodeURIComponent(file.storedName)}', '${file.originalName}')" title="Trash">${iconTrash}</button>
                </div>
            `;
            filesContainer.appendChild(row);
        });
    } catch (error) {
        console.error("Failed to fetch files:", error);
    }
}

async function fetchTrashedFiles() {
    try {
        const folderRes = await fetch("/api/folders/trash");
        const folders = await folderRes.json();

        const fileRes = await fetch("/api/files/trash");
        const files = await fileRes.json();

        const container = document.getElementById("trashContainer");
        container.innerHTML = "";

        if (folders.length === 0 && files.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    ${iconTrash}
                    <p>Trash is empty</p>
                </div>
            `;
            return;
        }

        // Trashed Folders
        folders.forEach(folder => {
            const row = document.createElement("div");
            row.className = "file-row trashed";
            
            row.innerHTML = `
                <div class="file-info">
                    <div class="file-icon folder-icon">${iconFolder}</div>
                    <div class="file-details">
                        <span class="file-name">${folder.name}</span>
                        <span class="file-meta">Deleted Folder</span>
                    </div>
                </div>
                <div class="file-actions">
                    <button class="btn-icon success" onclick="restoreFolder(${folder.id})" title="Restore">${iconRestore}</button>
                    <button class="btn-icon danger" onclick="permDeleteFolder(${folder.id}, '${folder.name}')" title="Delete Permanently">${iconTrash}</button>
                </div>
            `;
            container.appendChild(row);
        });

        // Trashed Files
        files.forEach(file => {
            const row = document.createElement("div");
            row.className = "file-row trashed";
            
            row.innerHTML = `
                <div class="file-info">
                    <div class="file-icon">${iconFile}</div>
                    <div class="file-details">
                        <span class="file-name" title="${file.originalName}">${file.originalName}</span>
                        <span class="file-meta">Deleted File • ${(file.size / 1024).toFixed(2)} KB</span>
                    </div>
                </div>
                <div class="file-actions">
                    <button class="btn-icon success" onclick="restoreFile('${encodeURIComponent(file.storedName)}')" title="Restore">${iconRestore}</button>
                    <button class="btn-icon danger" onclick="permDeleteFile('${encodeURIComponent(file.storedName)}', '${file.originalName}')" title="Delete Permanently">${iconTrash}</button>
                </div>
            `;
            container.appendChild(row);
        });
    } catch (error) {
        console.error("Failed to fetch trashed files:", error);
    }
}

// Actions
async function deleteFolder(id, name) {
    if(confirm(`Move folder '${name}' to trash?`)) {
        await fetch(`/api/folders/${id}`, {method: 'DELETE'});
    }
}
async function deleteFile(storedName, originalName) {
    if (confirm(`Move file '${originalName}' to trash?`)) {
        await fetch(`/api/files/${storedName}`, { method: "DELETE" });
    }
}
async function restoreFolder(id) {
    await fetch(`/api/folders/${id}/restore`, { method: "POST" });
}
async function restoreFile(storedName) {
    await fetch(`/api/files/${storedName}/restore`, { method: "POST" });
}
async function permDeleteFolder(id, name) {
    if (confirm(`Permanently delete folder '${name}'? This cannot be undone.`)) {
        await fetch(`/api/folders/${id}/permanent`, { method: "DELETE" });
    }
}
async function permDeleteFile(storedName, name) {
    if (confirm(`Permanently delete file '${name}'? This cannot be undone.`)) {
        await fetch(`/api/files/${storedName}/permanent`, { method: "DELETE" });
    }
}

// Modals
function showModal(id) { 
    const modal = document.getElementById(id);
    modal.style.display = 'flex';
    // Small delay to allow display block to apply before opacity transition
    setTimeout(() => modal.classList.add('active'), 10);
}
function closeModal(id) { 
    const modal = document.getElementById(id);
    modal.classList.remove('active');
    setTimeout(() => modal.style.display = 'none', 200);
}

function showCreateFolderModal() {
    document.getElementById('folderNameInput').value = '';
    showModal('createFolderModal');
}

function showRenameFolderModal(id, oldName) {
    document.getElementById('renameFolderId').value = id;
    document.getElementById('renameFolderInput').value = oldName;
    showModal('renameFolderModal');
}

async function createFolder() {
    const name = document.getElementById('folderNameInput').value.trim();
    if (!name) return;
    
    let url = `/api/folders?name=${encodeURIComponent(name)}`;
    if (currentFolderId) url += `&parentId=${currentFolderId}`;
    
    try {
        const res = await fetch(url, { method: 'POST' });
        if (res.ok) {
            closeModal('createFolderModal');
            fetchFiles();
        } else {
            alert("Failed to create folder.");
        }
    } catch(e) { alert("Error creating folder"); }
}

async function renameFolder() {
    const id = document.getElementById('renameFolderId').value;
    const name = document.getElementById('renameFolderInput').value.trim();
    if (!name) return;
    
    try {
        const res = await fetch(`/api/folders/${id}/rename?name=${encodeURIComponent(name)}`, { method: 'PUT' });
        if (res.ok) {
            closeModal('renameFolderModal');
            fetchFiles();
        } else {
            alert("Failed to rename folder.");
        }
    } catch(e) { alert("Error renaming folder"); }
}

// Hashing Helper
function calculateChecksum(file) {
    return new Promise((resolve, reject) => {
        const worker = new Worker('/hashWorker.js');
        
        worker.onmessage = function(e) {
            if (e.data.success) {
                resolve(e.data.hash);
            } else {
                reject(new Error(e.data.error));
            }
            worker.terminate();
        };

        worker.onerror = function(e) {
            reject(new Error("Worker failed to hash file."));
            worker.terminate();
        };

        worker.postMessage(file);
    });
}

// Upload Handling
const fileInput = document.getElementById('fileInput');
const uploadStatus = document.getElementById('uploadStatus');

if(fileInput) {
    fileInput.addEventListener('change', () => {
        if(fileInput.files.length > 0) {
            uploadForm.dispatchEvent(new Event('submit'));
        }
    });
}

if(uploadForm) {
    uploadForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        const file = fileInput.files[0];
        if (!file) return;

        try {
            uploadStatus.innerText = "Verifying file uniqueness...";
            const checksum = await calculateChecksum(file);
            let url = `/api/files/check?checksum=${encodeURIComponent(checksum)}`;
            if (currentFolderId) url += `&folderId=${currentFolderId}`;

            const res = await fetch(url);
            if (!res.ok) throw new Error("Failed to verify file.");
            
            const exists = await res.json();
            
            if (exists) {
                pendingDuplicateFile = file;
                showModal("duplicateModal");
            } else {
                uploadFile(file, "NONE");
            }
        } catch (error) {
            uploadStatus.innerText = error.message;
            uploadStatus.style.color = "var(--danger-color)";
        }
    });
}

document.getElementById("btnKeepBoth")?.addEventListener("click", () => {
    closeModal("duplicateModal");
    if (pendingDuplicateFile) uploadFile(pendingDuplicateFile, "KEEP_BOTH");
});
document.getElementById("btnReplace")?.addEventListener("click", () => {
    closeModal("duplicateModal");
    if (pendingDuplicateFile) uploadFile(pendingDuplicateFile, "REPLACE");
});
document.getElementById("btnCancelDup")?.addEventListener("click", () => {
    closeModal("duplicateModal");
    pendingDuplicateFile = null;
    uploadStatus.innerText = "Upload cancelled.";
    uploadStatus.style.color = "var(--text-muted)";
    fileInput.value = "";
});

let currentUploadAbortController = null;
let isUploadPaused = false;

document.getElementById("pauseBtn")?.addEventListener("click", () => {
    isUploadPaused = true;
    if (currentUploadAbortController) {
        currentUploadAbortController.abort();
    }
    document.getElementById("pauseBtn").style.display = "none";
    document.getElementById("resumeBtn").style.display = "block";
    uploadStatus.innerText = "Upload paused.";
});

document.getElementById("resumeBtn")?.addEventListener("click", () => {
    isUploadPaused = false;
    document.getElementById("resumeBtn").style.display = "none";
    document.getElementById("pauseBtn").style.display = "block";
    
    const file = fileInput.files[0];
    if (file) {
        const storageKey = 'upload_session_' + file.name + '_' + file.size;
        const sessionId = localStorage.getItem(storageKey);
        if (sessionId) {
            uploadFile(file, "NONE", sessionId); 
        } else {
            uploadForm.dispatchEvent(new Event('submit'));
        }
    }
});

async function uploadFile(file, duplicateAction, sessionId = null) {
    const CHUNK_SIZE = 20 * 1024 * 1024;
    const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
    const storageKey = 'upload_session_' + file.name + '_' + file.size;
    
    const progressContainer = document.getElementById("progressContainer");
    const progressBar = document.getElementById("progressBar");
    const pauseResumeContainer = document.getElementById("pauseResumeContainer");
    const pauseBtn = document.getElementById("pauseBtn");
    const resumeBtn = document.getElementById("resumeBtn");
    
    isUploadPaused = false;
    let uploadedChunks = [];

    if (!sessionId) sessionId = localStorage.getItem(storageKey);

    if (sessionId) {
        try {
            const statusRes = await fetch(`/api/files/upload/session/${sessionId}/status`);
            if (statusRes.ok) {
                const status = await statusRes.json();
                uploadedChunks = status.uploadedChunks;
                uploadStatus.innerText = "Resuming upload...";
            } else {
                sessionId = null;
                localStorage.removeItem(storageKey);
            }
        } catch(e) {
            sessionId = null;
        }
    }

    if (!sessionId) {
        let initUrl = `/api/files/upload/session?originalName=${encodeURIComponent(file.name)}&contentType=${encodeURIComponent(file.type)}&totalSize=${file.size}&totalChunks=${totalChunks}`;
        if (currentFolderId) initUrl += `&folderId=${currentFolderId}`;
        
        const initRes = await fetch(initUrl, { method: "POST" });
        if (!initRes.ok) {
            uploadStatus.innerText = "Failed to initialize upload.";
            uploadStatus.style.color = "var(--danger-color)";
            return;
        }
        const sessionData = await initRes.json();
        sessionId = sessionData.id;
        localStorage.setItem(storageKey, sessionId);
    }

    progressContainer.style.display = "block";
    pauseResumeContainer.style.display = "flex";
    pauseBtn.style.display = "block";
    resumeBtn.style.display = "none";
    uploadStatus.style.color = "var(--text-main)";

    for (let i = 0; i < totalChunks; i++) {
        if (isUploadPaused) return;

        const progressPercent = Math.round((i / totalChunks) * 100);
        progressBar.style.width = progressPercent + "%";
        uploadStatus.innerText = `Uploading... ${progressPercent}%`;

        if (uploadedChunks.includes(i)) continue;

        const start = i * CHUNK_SIZE;
        const end = Math.min(start + CHUNK_SIZE, file.size);
        const chunk = file.slice(start, end);

        const formData = new FormData();
        formData.append("file", chunk);
        formData.append("chunkIndex", i);

        currentUploadAbortController = new AbortController();

        try {
            const chunkRes = await fetch(`/api/files/upload/session/${sessionId}/part/${i}`, {
                method: "PUT",
                body: formData,
                signal: currentUploadAbortController.signal
            });
            if (!chunkRes.ok) throw new Error("Chunk upload failed");
        } catch (e) {
            if (e.name === 'AbortError') {
                console.log("Upload paused at chunk", i);
                return;
            }
            throw e;
        }
    }
    
    if (isUploadPaused) return;

    uploadStatus.innerText = "Finalizing upload...";
    progressBar.style.width = "100%";
    pauseResumeContainer.style.display = "none";
    
    try {
        const completeResponse = await fetch(`/api/files/upload/session/${sessionId}/complete?duplicateAction=${duplicateAction}`, {
            method: "POST"
        });

        if (completeResponse.ok) {
            uploadStatus.innerText = "File uploaded successfully!";
            uploadStatus.style.color = "var(--success-color)";
            fileInput.value = "";
            localStorage.removeItem(storageKey);
            setTimeout(() => { progressContainer.style.display = "none"; uploadStatus.innerText = ""; }, 3000);
        } else if (completeResponse.status === 409) {
            uploadStatus.innerText = "A duplicate collision occurred during upload.";
            uploadStatus.style.color = "var(--danger-color)";
            fileInput.value = "";
            progressContainer.style.display = "none";
            localStorage.removeItem(storageKey);
        } else {
            throw new Error(await completeResponse.text());
        }
    } catch (e) {
        uploadStatus.innerText = "Upload failed: " + e.message;
        uploadStatus.style.color = "var(--danger-color)";
        progressContainer.style.display = "none";
        pauseResumeContainer.style.display = "none";
    }
}

function connectWebSocket() {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = protocol + "//" + window.location.host + "/ws/files";
    const socket = new WebSocket(wsUrl);
    socket.onopen = () => console.log("WebSocket connected.");
    socket.onmessage = (e) => {
        if (e.data === "FILE_UPDATED") {
            if (currentView === 'files') fetchFiles();
            else fetchTrashedFiles();
        }
    };
    socket.onclose = () => setTimeout(connectWebSocket, 5000);
}

// Init
fetchFiles();
connectWebSocket();
