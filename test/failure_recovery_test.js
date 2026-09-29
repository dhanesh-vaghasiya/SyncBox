const crypto = require('crypto');
const { execSync } = require('child_process');

const API_BASE = 'http://localhost:8080';
const FILE_SIZE = 10 * 1024 * 1024; // 10MB
const CHUNK_SIZE = 2 * 1024 * 1024; // 2MB
const TOTAL_CHUNKS = 5;

async function doLogin(username, password) {
    const loginData = new URLSearchParams();
    loginData.append('username', username);
    loginData.append('password', password);
    const loginRes = await fetch(`${API_BASE}/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: loginData.toString(),
        redirect: 'manual'
    });
    
    for (const c of loginRes.headers.getSetCookie()) {
        if (c.startsWith('JSESSIONID=')) return c.split(';')[0];
    }
    return null;
}

async function uploadChunk(cookie, sessionId, chunkIndex, chunkData) {
    const formData = new FormData();
    formData.append('file', new Blob([chunkData], { type: 'text/plain' }), 'chunk.txt');
    formData.append('chunkIndex', chunkIndex);
    
    const res = await fetch(`${API_BASE}/api/files/upload/session/${sessionId}/part/${chunkIndex}`, {
        method: 'PUT',
        headers: { 'Cookie': cookie },
        body: formData
    });
    return res.ok;
}

async function waitForBackend() {
    console.log("Waiting for backend to become healthy...");
    while (true) {
        try {
            const res = await fetch(`${API_BASE}/login.html`, { signal: AbortSignal.timeout(3000) });
            if (res.ok) {
                console.log("Backend is UP!");
                return;
            }
        } catch (e) {
            // Ignore connection refused
        }
        await new Promise(r => setTimeout(r, 2000));
    }
}

async function runTest() {
    console.log("========== FAILURE RECOVERY TEST ==========\n");
    const username = `recovery_${Date.now()}`;
    const password = 'password123';
    const fileName = `recovery_test.txt`;

    // 1. Auth & Session
    const signupData = new URLSearchParams();
    signupData.append('username', username);
    signupData.append('password', password);
    await fetch(`${API_BASE}/api/auth/signup`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: signupData.toString() });

    let cookie = await doLogin(username, password);

    console.log("Creating 10MB upload session...");
    const sessRes = await fetch(`${API_BASE}/api/files/upload/session?originalName=${fileName}&contentType=text/plain&totalSize=${FILE_SIZE}&totalChunks=${TOTAL_CHUNKS}`, {
        method: 'POST',
        headers: { 'Cookie': cookie }
    });
    const sessionId = (await sessRes.json()).id;
    console.log(`Upload Session ID: ${sessionId}`);

    // Generate 10MB dummy data
    const fullData = new Uint8Array(FILE_SIZE);
    fullData.fill(77); // 'M'
    const expectedHashFn = crypto.createHash('sha256');
    expectedHashFn.update(fullData);
    const expectedHash = expectedHashFn.digest('hex');

    // 2. Upload first 2 chunks
    console.log("\nPhase 1: Uploading Chunk 0 and Chunk 1...");
    for (let i = 0; i < 2; i++) {
        const chunk = fullData.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE);
        const ok = await uploadChunk(cookie, sessionId, i, chunk);
        console.log(`Chunk ${i} upload: ${ok ? 'SUCCESS' : 'FAILED'}`);
    }

    // 3. Kill and restart backend
    console.log("\nPhase 2: STOPPING THE BACKEND to simulate crash...");
    execSync('docker compose restart app', { stdio: 'inherit' });
    
    // Wait for backend to come back
    await waitForBackend();

    // Re-login to get a new session cookie (since memory JSESSIONID was wiped by restart)
    console.log("Re-authenticating after crash...");
    cookie = await doLogin(username, password);
    console.log("Acquired new JSESSIONID.");

    // 4. Resume upload
    console.log("\nPhase 3: Resuming upload from Chunk 2...");
    for (let i = 2; i < TOTAL_CHUNKS; i++) {
        const chunk = fullData.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE);
        const ok = await uploadChunk(cookie, sessionId, i, chunk);
        console.log(`Chunk ${i} upload: ${ok ? 'SUCCESS' : 'FAILED'}`);
    }

    // 5. Complete upload
    console.log("\nPhase 4: Completing upload...");
    const completeRes = await fetch(`${API_BASE}/api/files/upload/session/${sessionId}/complete?duplicateAction=KEEP_BOTH`, {
        method: 'POST',
        headers: { 'Cookie': cookie }
    });
    
    if (!completeRes.ok) {
        console.log("FAIL: Final completion failed.");
        return;
    }

    // Wait for assembly
    let storedName = null;
    for (let i = 0; i < 20; i++) {
        const res = await fetch(`${API_BASE}/api/files/list`, { headers: { 'Cookie': cookie } });
        if (res.ok) {
            const files = await res.json();
            const found = files.find(f => f.originalName === fileName);
            if (found) { storedName = found.storedName; break; }
        }
        await new Promise(r => setTimeout(r, 1000));
    }

    // 6. Download and verify
    console.log("\nPhase 5: Downloading and Verifying...");
    const dlRes = await fetch(`${API_BASE}/api/files/download/${storedName}`, { headers: { 'Cookie': cookie } });
    const fileHashFn = crypto.createHash('sha256');
    for await (const chunk of dlRes.body) fileHashFn.update(chunk);
    
    const downloadedHash = fileHashFn.digest('hex');
    const integrityPass = (downloadedHash === expectedHash);

    console.log(`  Expected SHA-256: ${expectedHash}`);
    console.log(`  Actual SHA-256:   ${downloadedHash}`);
    console.log(`  Integrity: ${integrityPass ? 'PASS' : 'FAIL'}`);

    console.log(`\nResult: ${integrityPass ? 'PASS (FAILURE RECOVERY SUCCESSFUL)' : 'FAIL'}`);
    console.log("===========================================\n");

    // Cleanup
    await fetch(`${API_BASE}/api/files/${storedName}/permanent`, { method: 'DELETE', headers: { 'Cookie': cookie } });
}

runTest().catch(console.error);
