let currentFolderId = null;
let pendingDuplicateFile = null;
let currentView = 'files'; // 'files' or 'trash'
let currentFetchedFolders = [];
let currentFetchedFiles = [];

// Elements
const filesContainer = document.getElementById('filesContainer');
const breadcrumb = document.getElementById('breadcrumb');
const uploadZone = document.getElementById('uploadZone');
const filesView = document.getElementById('filesView');
const trashView = document.getElementById('trashView');
const recentView = document.getElementById('recentView');
const uploadForm = document.getElementById('uploadForm');

// Navigation
function switchView(view) {
    currentView = view;
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    
    if (view === 'files') {
        document.getElementById('nav-files').classList.add('active');
        filesView.style.display = 'block';
        trashView.style.display = 'none';
        recentView.style.display = 'none';
        fetchFiles();
    } else if (view === 'recent') {
        document.getElementById('nav-recent').classList.add('active');
        filesView.style.display = 'none';
        trashView.style.display = 'none';
        recentView.style.display = 'block';
        fetchRecentFiles();
    } else if (view === 'trash') {
        document.getElementById('nav-trash').classList.add('active');
        filesView.style.display = 'none';
        trashView.style.display = 'block';
        recentView.style.display = 'none';
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
        currentFetchedFolders = await folderRes.json();

        let fileUrl = "/api/files/list";
        if (currentFolderId) fileUrl += `?folderId=${currentFolderId}`;
        const fileRes = await fetch(fileUrl);
        currentFetchedFiles = await fileRes.json();
        
        // Reset search bar when navigating
        const searchInput = document.getElementById('searchInput');
        if (searchInput) searchInput.value = '';

        renderFilesAndFolders(currentFetchedFolders, currentFetchedFiles);
    } catch (error) {
        console.error("Failed to fetch files:", error);
    }
}

function handleSearch() {
    const query = document.getElementById('searchInput').value.toLowerCase();
    if (!query) {
        renderFilesAndFolders(currentFetchedFolders, currentFetchedFiles);
        return;
    }
    
    const filteredFolders = currentFetchedFolders.filter(f => f.name.toLowerCase().includes(query));
    const filteredFiles = currentFetchedFiles.filter(f => f.originalName.toLowerCase().includes(query));
    renderFilesAndFolders(filteredFolders, filteredFiles);
}

function toggleSelectAll(checked, containerId) {
    const checkboxes = document.querySelectorAll(`#${containerId} .item-checkbox`);
    checkboxes.forEach(cb => cb.checked = checked);
    updateBulkActions(containerId);
}

function updateBulkActions(containerId) {
    const checkboxes = document.querySelectorAll(`#${containerId} .item-checkbox:checked`);
    const actionContainer = document.getElementById(`bulkActions_${containerId}`);
    const selectAllCb = document.getElementById(`selectAll_${containerId}`);
    
    if (selectAllCb) {
        const allCheckboxes = document.querySelectorAll(`#${containerId} .item-checkbox`);
        selectAllCb.checked = allCheckboxes.length > 0 && allCheckboxes.length === checkboxes.length;
    }
    
    if (!actionContainer) return;
    
    if (checkboxes.length > 0) {
        actionContainer.style.display = 'flex';
        document.getElementById(`bulkCount_${containerId}`).innerText = `${checkboxes.length} selected`;
    } else {
        actionContainer.style.display = 'none';
    }
}

async function bulkDelete(containerId) {
    const checked = document.querySelectorAll(`#${containerId} .item-checkbox:checked`);
    if (checked.length === 0) return;
    
    if (!confirm(`Are you sure you want to move ${checked.length} selected items to trash?`)) return;
    
    for (const cb of checked) {
        const type = cb.getAttribute('data-type');
        const id = cb.getAttribute('data-id');
        
        try {
            if (type === 'folder') {
                await fetch(`/api/folders/${id}`, {method: 'DELETE'});
            } else if (type === 'file') {
                await fetch(`/api/files/${id}`, { method: "DELETE" });
            }
        } catch (e) { console.error(e); }
    }
    
    if (containerId === 'filesContainer') fetchFiles();
    else if (containerId === 'recentContainer') fetchRecentFiles();
}

async function bulkRestore(containerId) {
    const checked = document.querySelectorAll(`#${containerId} .item-checkbox:checked`);
    if (checked.length === 0) return;
    
    if (!confirm(`Are you sure you want to restore ${checked.length} selected items?`)) return;
    
    for (const cb of checked) {
        const type = cb.getAttribute('data-type');
        const id = cb.getAttribute('data-id');
        
        try {
            if (type === 'folder') {
                await fetch(`/api/folders/${id}/restore`, {method: 'POST'});
            } else if (type === 'file') {
                await fetch(`/api/files/${id}/restore`, { method: "POST" });
            }
        } catch (e) { console.error(e); }
    }
    fetchTrashedFiles();
}

async function bulkPermDelete(containerId) {
    const checked = document.querySelectorAll(`#${containerId} .item-checkbox:checked`);
    if (checked.length === 0) return;
    
    if (!confirm(`Are you sure you want to PERMANENTLY delete ${checked.length} selected items? This cannot be undone.`)) return;
    
    for (const cb of checked) {
        const type = cb.getAttribute('data-type');
        const id = cb.getAttribute('data-id');
        
        try {
            if (type === 'folder') {
                await fetch(`/api/folders/${id}/permanent`, {method: 'DELETE'});
            } else if (type === 'file') {
                await fetch(`/api/files/${id}/permanent`, { method: "DELETE" });
            }
        } catch (e) { console.error(e); }
    }
    fetchTrashedFiles();
}

function renderFilesAndFolders(folders, files) {
    filesContainer.innerHTML = "";

    if (folders.length === 0 && files.length === 0) {
        filesContainer.innerHTML = `
            <div class="empty-state">
                ${iconFolder}
                <p>No files or folders found</p>
            </div>
        `;
        return;
    }

    const headerRow = document.createElement("div");
    headerRow.className = "file-row header-row";
    headerRow.style.background = "transparent";
    headerRow.style.boxShadow = "none";
    headerRow.style.paddingBottom = "8px";
    headerRow.innerHTML = `
        <div style="display: flex; align-items: center; gap: 16px;">
            <input type="checkbox" id="selectAll_filesContainer" onchange="toggleSelectAll(this.checked, 'filesContainer')" style="width: 18px; height: 18px; cursor: pointer;">
            <label for="selectAll_filesContainer" style="font-weight: 600; color: var(--text-main); cursor: pointer;">Select All</label>
        </div>
        <div id="bulkActions_filesContainer" style="display: none; align-items: center; gap: 12px;">
            <span id="bulkCount_filesContainer" style="color: var(--text-muted); font-size: 14px;">0 selected</span>
            <button class="btn-icon danger" onclick="bulkDelete('filesContainer')" title="Delete Selected">
                ${iconTrash}
            </button>
        </div>
    `;
    filesContainer.appendChild(headerRow);

    // Render Folders
    folders.forEach(folder => {
        const row = document.createElement("div");
        row.className = "file-row";
        
        row.innerHTML = `
            <div style="display: flex; align-items: center; padding-right: 16px;">
                <input type="checkbox" class="item-checkbox" data-type="folder" data-id="${folder.id}" onchange="updateBulkActions('filesContainer')" style="width: 18px; height: 18px; cursor: pointer;">
            </div>
            <div class="file-info" onclick="navigateTo(${folder.id})" style="flex: 1; cursor: pointer;">
                <div class="file-icon folder-icon">${iconFolder}</div>
                <div class="file-details">
                    <span class="file-name">${folder.name}</span>
                    <span class="file-meta">Folder</span>
                </div>
            </div>
            <div class="file-actions">
                <button class="btn-icon" onclick="openMoveCopyModal('folder', '${folder.id}')" title="Move or Copy"><svg style="width: 20px; height: 20px;" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4"></path></svg></button>
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
            <div style="display: flex; align-items: center; padding-right: 16px;">
                <input type="checkbox" class="item-checkbox" data-type="file" data-id="${encodeURIComponent(file.storedName)}" onchange="updateBulkActions('filesContainer')" style="width: 18px; height: 18px; cursor: pointer;">
            </div>
            <div class="file-info" style="flex: 1;">
                <div class="file-icon">${iconFile}</div>
                <div class="file-details">
                    <span class="file-name" title="${file.originalName}">${file.originalName}</span>
                    <span class="file-meta">${(file.size / 1024).toFixed(2)} KB • ${new Date(file.uploadedAt).toLocaleDateString()}</span>
                </div>
            </div>
            <div class="file-actions">
                <button class="btn-icon" onclick="openMoveCopyModal('file', '${encodeURIComponent(file.storedName)}')" title="Move or Copy"><svg style="width: 20px; height: 20px;" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4"></path></svg></button>
                <a href="/api/files/download/${encodeURIComponent(file.storedName)}" download="${file.originalName}" class="btn-icon" title="Download">${iconDownload}</a>
                <button class="btn-icon danger" onclick="deleteFile('${encodeURIComponent(file.storedName)}', '${file.originalName}')" title="Trash">${iconTrash}</button>
            </div>
        `;
        filesContainer.appendChild(row);
    });
}


async function fetchRecentFiles() {
    try {
        const fileRes = await fetch("/api/files/recent");
        const files = await fileRes.json();
        
        const container = document.getElementById('recentContainer');
        container.innerHTML = "";
        
        if (files.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    ${iconFile}
                    <p>No recent files</p>
                </div>
            `;
            return;
        }

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
                    <button class="btn-icon" onclick="openMoveCopyModal('file', '${encodeURIComponent(file.storedName)}')" title="Move or Copy"><svg style="width: 20px; height: 20px;" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4"></path></svg></button>
                    <a href="/api/files/download/${encodeURIComponent(file.storedName)}" download="${file.originalName}" class="btn-icon" title="Download">${iconDownload}</a>
                    <button class="btn-icon danger" onclick="deleteFile('${encodeURIComponent(file.storedName)}', '${file.originalName}')" title="Trash">${iconTrash}</button>
                </div>
            `;
            container.appendChild(row);
        });
    } catch (error) {
        console.error("Failed to fetch recent files:", error);
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

        const headerRow = document.createElement("div");
        headerRow.className = "file-row header-row";
        headerRow.style.background = "transparent";
        headerRow.style.boxShadow = "none";
        headerRow.style.paddingBottom = "8px";
        headerRow.innerHTML = `
            <div style="display: flex; align-items: center; gap: 16px;">
                <input type="checkbox" id="selectAll_trashContainer" onchange="toggleSelectAll(this.checked, 'trashContainer')" style="width: 18px; height: 18px; cursor: pointer;">
                <label for="selectAll_trashContainer" style="font-weight: 600; color: var(--text-main); cursor: pointer;">Select All</label>
            </div>
            <div id="bulkActions_trashContainer" style="display: none; align-items: center; gap: 12px;">
                <span id="bulkCount_trashContainer" style="color: var(--text-muted); font-size: 14px;">0 selected</span>
                <button class="btn-icon success" onclick="bulkRestore('trashContainer')" title="Restore Selected">
                    ${iconRestore}
                </button>
                <button class="btn-icon danger" onclick="bulkPermDelete('trashContainer')" title="Delete Permanently">
                    ${iconTrash}
                </button>
            </div>
        `;
        container.appendChild(headerRow);

        // Trashed Folders
        folders.forEach(folder => {
            const row = document.createElement("div");
            row.className = "file-row trashed";
            
            row.innerHTML = `
                <div style="display: flex; align-items: center; padding-right: 16px;">
                    <input type="checkbox" class="item-checkbox" data-type="folder" data-id="${folder.id}" onchange="updateBulkActions('trashContainer')" style="width: 18px; height: 18px; cursor: pointer;">
                </div>
                <div class="file-info" style="flex: 1;">
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
                <div style="display: flex; align-items: center; padding-right: 16px;">
                    <input type="checkbox" class="item-checkbox" data-type="file" data-id="${encodeURIComponent(file.storedName)}" onchange="updateBulkActions('trashContainer')" style="width: 18px; height: 18px; cursor: pointer;">
                </div>
                <div class="file-info" style="flex: 1;">
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

if(fileInput) {
    fileInput.addEventListener('change', () => {
        if(fileInput.files.length > 0) {
            uploadManager.handleFilesSelection(fileInput.files);
            fileInput.value = ""; // Reset input so same files can be selected again
        }
    });
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

// Global Drag and Drop
const mainDropZone = document.getElementById('mainDropZone');
let dragCounter = 0;

document.body.addEventListener('dragenter', (e) => {
    e.preventDefault();
    dragCounter++;
    document.body.classList.add('drag-active');
});

document.body.addEventListener('dragover', (e) => {
    e.preventDefault(); // necessary to allow dropping
});

document.body.addEventListener('dragleave', (e) => {
    e.preventDefault();
    dragCounter--;
    if (dragCounter === 0) {
        document.body.classList.remove('drag-active');
    }
});

document.body.addEventListener('drop', (e) => {
    e.preventDefault();
    dragCounter = 0;
    document.body.classList.remove('drag-active');
    
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        uploadManager.handleFilesSelection(e.dataTransfer.files);
    }
});

// Move / Copy Logic
let allFolders = [];
let pendingMoveCopyData = null;

async function openMoveCopyModal(type, id) {
    document.getElementById('moveCopyItemId').value = id;
    document.getElementById('moveCopyItemIsFolder').value = (type === 'folder');
    
    // Fetch all folders
    try {
        const res = await fetch('/api/folders/all');
        allFolders = await res.json();
        
        const select = document.getElementById('moveCopyTargetFolder');
        select.innerHTML = '<option value="">/ (Root)</option>';
        
        // Build map for paths
        const folderMap = {};
        allFolders.forEach(f => folderMap[f.id] = f);
        
        const parentMap = {};
        allFolders.forEach(f => parentMap[f.id] = f.parent ? f.parent.id : null);
        const getPathLocal = (fid) => {
            let path = '';
            let currentId = fid;
            while(currentId) {
                const f = folderMap[currentId];
                if(!f) break;
                path = path ? f.name + ' / ' + path : f.name;
                currentId = parentMap[currentId];
            }
            return path;
        };
        
        // Populate select
        const options = allFolders.map(f => ({
            id: f.id,
            path: getPathLocal(f.id)
        })).sort((a,b) => a.path.localeCompare(b.path));
        
        options.forEach(opt => {
            const el = document.createElement('option');
            el.value = opt.id;
            el.textContent = '/ ' + opt.path;
            // Prevent selecting self as target if it is a folder
            if (type === 'folder' && parseInt(id) === opt.id) {
                el.disabled = true;
            }
            select.appendChild(el);
        });
        
        showModal('moveCopyModal');
    } catch (e) {
        console.error('Failed to load folders', e);
        alert('Failed to load folders');
    }
}

async function executeMoveCopy(action, duplicateAction = 'NONE') {
    const id = document.getElementById('moveCopyItemId').value;
    const isFolder = document.getElementById('moveCopyItemIsFolder').value === 'true';
    const targetFolderId = document.getElementById('moveCopyTargetFolder').value;
    
    let url = '';
    let method = action === 'copy' ? 'POST' : 'PUT';
    
    if (isFolder) {
        url = `/api/folders/${id}/${action}`;
        if (targetFolderId) url += `?parentId=${targetFolderId}`;
    } else {
        url = `/api/files/${id}/${action}`;
        if (targetFolderId) url += `?folderId=${targetFolderId}`;
        url += (url.includes('?') ? '&' : '?') + `duplicateAction=${duplicateAction}`;
    }
    
    try {
        const res = await fetch(url, { method });
        if (res.ok) {
            closeModal('moveCopyModal');
            if (currentView === 'files') fetchFiles();
        } else if (res.status === 409 && !isFolder) {
            // Duplicate detected!
            closeModal('moveCopyModal');
            pendingMoveCopyData = { action, id, targetFolderId };
            showModal('duplicateModal');
        } else {
            const text = await res.text();
            alert('Operation failed: ' + text);
        }
    } catch (e) {
        console.error(e);
        alert('Operation failed');
    }
}

// Override duplicate modal handlers to also support move/copy
const origBtnKeepBoth = document.getElementById("btnKeepBoth");
const origBtnReplace = document.getElementById("btnReplace");
const origBtnCancelDup = document.getElementById("btnCancelDup");

if(origBtnKeepBoth) {
    origBtnKeepBoth.onclick = () => {
        closeModal("duplicateModal");
        if (pendingDuplicateFile) uploadFile(pendingDuplicateFile, "KEEP_BOTH");
        else if (pendingMoveCopyData) executeMoveCopy(pendingMoveCopyData.action, "KEEP_BOTH");
        pendingMoveCopyData = null;
    };
}
if(origBtnReplace) {
    origBtnReplace.onclick = () => {
        closeModal("duplicateModal");
        if (pendingDuplicateFile) uploadFile(pendingDuplicateFile, "REPLACE");
        else if (pendingMoveCopyData) executeMoveCopy(pendingMoveCopyData.action, "REPLACE");
        pendingMoveCopyData = null;
    };
}
if(origBtnCancelDup) {
    origBtnCancelDup.onclick = () => {
        closeModal("duplicateModal");
        pendingDuplicateFile = null;
        pendingMoveCopyData = null;
        fileInput.value = "";
    };
}

function toggleSidebar() {
    const sidebar = document.querySelector('.sidebar');
    const overlay = document.getElementById('sidebarOverlay');
    sidebar.classList.toggle('active');
    
    if (sidebar.classList.contains('active')) {
        overlay.classList.add('active');
    } else {
        overlay.classList.remove('active');
    }
}
