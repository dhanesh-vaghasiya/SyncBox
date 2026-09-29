const fs = require('fs');
const crypto = require('crypto');

const API_BASE = 'http://localhost:8080';
const NUM_USERS = 100;
const FILE_SIZE = 100 * 1024 * 1024; // 100 MB
const CHUNK_SIZE = 2 * 1024 * 1024; // 2 MB
const TOTAL_CHUNKS = Math.ceil(FILE_SIZE / CHUNK_SIZE);

// Global Stats
const stats = {
    successful: 0,
    failed: 0,
    failedChunks: 0,
    uploadTimes: [],
    integrityPassed: 0,
    integrityFailed: 0,
    httpErrors: {
        400: 0,
        401: 0,
        404: 0,
        409: 0,
        500: 0
    }
};

function trackHttpError(status) {
    if (status >= 400 && status < 600) {
        if (stats.httpErrors[status] !== undefined) {
            stats.httpErrors[status]++;
        } else {
            stats.httpErrors[status] = 1;
        }
    }
}

// Generate Expected Hash
console.log("Generating 100MB dummy chunk and computing expected hash...");
const chunkData = new Uint8Array(CHUNK_SIZE);
chunkData.fill(65); // 'A'

const hashFn = crypto.createHash('sha256');
for (let i = 0; i < TOTAL_CHUNKS; i++) {
    const currentChunkSize = (i === TOTAL_CHUNKS - 1) && (FILE_SIZE % CHUNK_SIZE !== 0) ? (FILE_SIZE % CHUNK_SIZE) : CHUNK_SIZE;
    hashFn.update(chunkData.slice(0, currentChunkSize));
}
const EXPECTED_HASH = hashFn.digest('hex');
console.log(`Expected SHA-256: ${EXPECTED_HASH}`);

// API Functions
async function signup(username, password) {
    const formData = new URLSearchParams();
    formData.append('username', username);
    formData.append('password', password);
    const res = await fetch(`${API_BASE}/api/auth/signup`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: formData.toString()
    });
    return res;
}

async function login(username, password) {
    const formData = new URLSearchParams();
    formData.append('username', username);
    formData.append('password', password);
    const res = await fetch(`${API_BASE}/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: formData.toString(),
        redirect: 'manual'
    });
    
    const cookies = res.headers.getSetCookie();
    let sessionCookie = '';
    for (const c of cookies) {
        if (c.startsWith('JSESSIONID=')) {
            sessionCookie = c.split(';')[0];
            break;
        }
    }
    return sessionCookie;
}

async function createSession(cookie, fileName) {
    const res = await fetch(`${API_BASE}/api/files/upload/session?originalName=${fileName}&contentType=text/plain&totalSize=${FILE_SIZE}&totalChunks=${TOTAL_CHUNKS}`, {
        method: 'POST',
        headers: { 'Cookie': cookie }
    });
    trackHttpError(res.status);
    const data = await res.json();
    return data.id;
}

async function uploadFullFile(cookie, sessionId, fileName) {
    const startTime = Date.now();
    for (let i = 0; i < TOTAL_CHUNKS; i++) {
        const formData = new FormData();
        const currentChunkSize = (i === TOTAL_CHUNKS - 1) && (FILE_SIZE % CHUNK_SIZE !== 0) ? (FILE_SIZE % CHUNK_SIZE) : CHUNK_SIZE;
        const blob = new Blob([chunkData.slice(0, currentChunkSize)], { type: 'text/plain' });
        
        formData.append('file', blob, 'chunk.txt');
        formData.append('chunkIndex', i);
        
        const res = await fetch(`${API_BASE}/api/files/upload/session/${sessionId}/part/${i}`, {
            method: 'PUT',
            headers: { 'Cookie': cookie },
            body: formData
        });
        trackHttpError(res.status);
        if (!res.ok) {
            stats.failedChunks++;
            return { status: res.status, error: `Chunk ${i} failed` };
        }
    }
    
    const completeRes = await fetch(`${API_BASE}/api/files/upload/session/${sessionId}/complete?duplicateAction=KEEP_BOTH`, {
        method: 'POST',
        headers: { 'Cookie': cookie }
    });
    trackHttpError(completeRes.status);
    
    if (!completeRes.ok) return { status: completeRes.status, error: "Complete failed" };
    
    const duration = Date.now() - startTime;
    return { status: 200, duration };
}

async function waitForFileAndVerify(cookie, fileName) {
    let storedName = null;
    let fileId = null;
    // Poll list endpoint until file appears (async complete takes a moment)
    for (let i = 0; i < 20; i++) {
        const res = await fetch(`${API_BASE}/api/files/list`, {
            headers: { 'Cookie': cookie }
        });
        if (res.ok) {
            const files = await res.json();
            const found = files.find(f => f.originalName === fileName);
            if (found) {
                storedName = found.storedName;
                fileId = found.id;
                break;
            }
        }
        await new Promise(r => setTimeout(r, 2000));
    }
    
    if (!storedName) {
        return { success: false, error: "File did not appear in list after complete" };
    }

    // Verify Integrity
    try {
        const res = await fetch(`${API_BASE}/api/files/download/${storedName}`, {
            headers: { 'Cookie': cookie }
        });
        if (!res.ok) return { success: false, error: "Download failed" };
        
        const fileHashFn = crypto.createHash('sha256');
        for await (const chunk of res.body) {
            fileHashFn.update(chunk);
        }
        
        const downloadedHash = fileHashFn.digest('hex');
        const integrityPass = (downloadedHash === EXPECTED_HASH);
        
        // Cleanup file permanently
        await fetch(`${API_BASE}/api/files/${storedName}/permanent`, {
            method: 'DELETE',
            headers: { 'Cookie': cookie }
        });
        
        return { success: integrityPass, error: integrityPass ? null : "Hash mismatch" };
    } catch (e) {
        return { success: false, error: e.message };
    }
}

async function runTest() {
    console.log(`\nStarting load test with ${NUM_USERS} concurrent users, each uploading a ${FILE_SIZE / 1024 / 1024}MB file...`);
    
    const users = [];
    for (let i = 0; i < NUM_USERS; i++) {
        const username = `loadtestuser${Date.now()}_${i}`;
        const password = 'password123';
        const fileName = `testfile_${i}.txt`;
        
        await signup(username, password);
        const cookie = await login(username, password);
        const sessionId = await createSession(cookie, fileName);
        
        users.push({ username, cookie, sessionId, fileName });
        if (i % 10 === 0) console.log(`Setup complete for ${i}/${NUM_USERS} users...`);
    }
    
    console.log(`\nFiring ${NUM_USERS} concurrent 100MB file uploads (${NUM_USERS * TOTAL_CHUNKS} total chunks)...`);
    const totalStartTime = Date.now();
    
    const promises = users.map(async u => {
        const uploadResult = await uploadFullFile(u.cookie, u.sessionId, u.fileName);
        if (uploadResult.status === 200) {
            stats.successful++;
            stats.uploadTimes.push(uploadResult.duration);
            
            const verifyResult = await waitForFileAndVerify(u.cookie, u.fileName);
            if (verifyResult.success) {
                stats.integrityPassed++;
            } else {
                stats.integrityFailed++;
                console.log(`Integrity fail for ${u.username}: ${verifyResult.error}`);
            }
        } else {
            stats.failed++;
            console.log(`Upload fail for ${u.username}: ${uploadResult.error}`);
        }
    });
    
    await Promise.all(promises);
    const totalTestTime = Date.now() - totalStartTime;
    
    // Print Summary
    const minTime = stats.uploadTimes.length > 0 ? Math.min(...stats.uploadTimes) / 1000 : 0;
    const maxTime = stats.uploadTimes.length > 0 ? Math.max(...stats.uploadTimes) / 1000 : 0;
    const avgTime = stats.uploadTimes.length > 0 ? (stats.uploadTimes.reduce((a, b) => a + b, 0) / stats.uploadTimes.length) / 1000 : 0;
    
    console.log('\n========== LOAD TEST RESULTS ==========');
    console.log('');
    console.log(`Users:              ${NUM_USERS}`);
    console.log(`File Size:          ${FILE_SIZE / 1024 / 1024} MB`);
    console.log(`Chunk Size:         ${CHUNK_SIZE / 1024 / 1024} MB`);
    console.log(`Total Chunks:       ${NUM_USERS * TOTAL_CHUNKS}`);
    console.log('');
    console.log(`Successful:         ${stats.successful}`);
    console.log(`Failed:             ${stats.failed}`);
    console.log(`Failed Chunks:      ${stats.failedChunks}`);
    console.log('');
    console.log(`Min Upload Time:    ${minTime.toFixed(1)} sec`);
    console.log(`Average:            ${avgTime.toFixed(1)} sec`);
    console.log(`Max Upload Time:    ${maxTime.toFixed(1)} sec`);
    console.log('');
    console.log(`Integrity Passed:   ${stats.integrityPassed}`);
    console.log(`Integrity Failed:   ${stats.integrityFailed}`);
    console.log('');
    console.log(`Total Test Time:    ${(totalTestTime / 1000).toFixed(1)} sec`);
    console.log('');
    console.log('HTTP Errors:');
    for (const [code, count] of Object.entries(stats.httpErrors)) {
        console.log(`${code}: ${count}`);
    }
    console.log('========================================');
}

runTest().catch(console.error);
