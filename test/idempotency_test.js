const crypto = require('crypto');

const API_BASE = 'http://localhost:8080';
const FILE_SIZE = 2 * 1024 * 1024; // 2 MB
const CHUNK_SIZE = 2 * 1024 * 1024; // 2 MB
const CHUNK_INDEX = 0;

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
    const res = await fetch(`${API_BASE}/api/files/upload/session?originalName=${fileName}&contentType=text/plain&totalSize=${FILE_SIZE}&totalChunks=1`, {
        method: 'POST',
        headers: { 'Cookie': cookie }
    });
    if (!res.ok) {
        console.error("createSession failed:", res.status, await res.text());
        process.exit(1);
    }
    const data = await res.json();
    return data.id;
}

async function uploadChunk(cookie, sessionId, chunkData) {
    const formData = new FormData();
    const blob = new Blob([chunkData], { type: 'text/plain' });
    formData.append('file', blob, 'chunk.txt');
    formData.append('chunkIndex', CHUNK_INDEX);
    
    try {
        const res = await fetch(`${API_BASE}/api/files/upload/session/${sessionId}/part/${CHUNK_INDEX}`, {
            method: 'PUT',
            headers: { 'Cookie': cookie },
            body: formData
        });
        return res.ok;
    } catch (e) {
        return false;
    }
}

async function waitForFileAndVerify(cookie, fileName, expectedHash) {
    let storedName = null;
    for (let i = 0; i < 20; i++) {
        const res = await fetch(`${API_BASE}/api/files/list`, {
            headers: { 'Cookie': cookie }
        });
        if (res.ok) {
            const files = await res.json();
            const found = files.find(f => f.originalName === fileName);
            if (found) {
                storedName = found.storedName;
                break;
            }
        }
        await new Promise(r => setTimeout(r, 1000));
    }
    
    if (!storedName) {
        return { success: false, downloadedHash: 'N/A', error: "File did not appear in list" };
    }

    try {
        const res = await fetch(`${API_BASE}/api/files/download/${storedName}`, {
            headers: { 'Cookie': cookie }
        });
        if (!res.ok) return { success: false, downloadedHash: 'N/A', error: "Download failed" };
        
        const fileHashFn = crypto.createHash('sha256');
        for await (const chunk of res.body) {
            fileHashFn.update(chunk);
        }
        
        const downloadedHash = fileHashFn.digest('hex');
        const integrityPass = (downloadedHash === expectedHash);
        
        return { success: integrityPass, downloadedHash, storedName };
    } catch (e) {
        return { success: false, downloadedHash: 'N/A', error: e.message };
    }
}

async function runTest() {
    const username = `idempotency_${Date.now()}`;
    const password = 'password123';
    const fileName = `idempotency_test.txt`;

    await signup(username, password);
    const cookie = await login(username, password);
    const sessionId = await createSession(cookie, fileName);

    const chunkData = new Uint8Array(CHUNK_SIZE);
    chunkData.fill(65);
    
    const expectedHashFn = crypto.createHash('sha256');
    expectedHashFn.update(chunkData);
    const expectedHash = expectedHashFn.digest('hex');

    console.log(`========== IDEMPOTENCY TEST ==========\n`);
    console.log(`Upload Session: ${sessionId}`);
    console.log(`Chunk Index: ${CHUNK_INDEX}`);
    console.log(`Chunk Size: 2 MB\n`);

    const results = {
        seq1: false, seq2: false, seq5: false, seq10: false,
        conc2: false, conc5: false, conc10: false
    };

    console.log(`Sequential duplicate test:`);
    // 1 Request
    results.seq1 = await uploadChunk(cookie, sessionId, chunkData);
    console.log(`  1 request   → ${results.seq1 ? 'PASS' : 'FAIL'}`);
    
    // 2 Requests
    let pass = true;
    for(let i=0; i<2; i++) if(!(await uploadChunk(cookie, sessionId, chunkData))) pass = false;
    results.seq2 = pass;
    console.log(`  2 requests  → ${results.seq2 ? 'PASS' : 'FAIL'}`);

    // 5 Requests
    pass = true;
    for(let i=0; i<5; i++) if(!(await uploadChunk(cookie, sessionId, chunkData))) pass = false;
    results.seq5 = pass;
    console.log(`  5 requests  → ${results.seq5 ? 'PASS' : 'FAIL'}`);

    // 10 Requests
    pass = true;
    for(let i=0; i<10; i++) if(!(await uploadChunk(cookie, sessionId, chunkData))) pass = false;
    results.seq10 = pass;
    console.log(` 10 requests  → ${results.seq10 ? 'PASS' : 'FAIL'}`);

    console.log(`\nConcurrent duplicate test:`);
    // 2 Concurrent
    let promises = [];
    for(let i=0; i<2; i++) promises.push(uploadChunk(cookie, sessionId, chunkData));
    let responses = await Promise.all(promises);
    results.conc2 = responses.every(r => r);
    console.log(`  2 requests  → ${results.conc2 ? 'PASS' : 'FAIL'}`);

    // 5 Concurrent
    promises = [];
    for(let i=0; i<5; i++) promises.push(uploadChunk(cookie, sessionId, chunkData));
    responses = await Promise.all(promises);
    results.conc5 = responses.every(r => r);
    console.log(`  5 requests  → ${results.conc5 ? 'PASS' : 'FAIL'}`);

    // 10 Concurrent
    promises = [];
    for(let i=0; i<10; i++) promises.push(uploadChunk(cookie, sessionId, chunkData));
    responses = await Promise.all(promises);
    results.conc10 = responses.every(r => r);
    console.log(` 10 requests  → ${results.conc10 ? 'PASS' : 'FAIL'}`);

    // Complete the upload
    const completeRes = await fetch(`${API_BASE}/api/files/upload/session/${sessionId}/complete?duplicateAction=KEEP_BOTH`, {
        method: 'POST',
        headers: { 'Cookie': cookie }
    });

    console.log(`\nFinal file:`);
    console.log(`  Expected SHA-256: ${expectedHash}`);
    
    if (completeRes.ok) {
        const verifyResult = await waitForFileAndVerify(cookie, fileName, expectedHash);
        console.log(`  Actual SHA-256:   ${verifyResult.downloadedHash}`);
        console.log(`  Integrity: ${verifyResult.success ? 'PASS' : 'FAIL'}`);
        
        const allRequestsPassed = Object.values(results).every(r => r);
        const isIdempotent = verifyResult.success && allRequestsPassed;
        
        console.log(`\nResult:`);
        console.log(`  ${isIdempotent ? 'IDEMPOTENT' : 'NOT IDEMPOTENT'}`);
        
        if (verifyResult.storedName) {
            await fetch(`${API_BASE}/api/files/${verifyResult.storedName}/permanent`, {
                method: 'DELETE',
                headers: { 'Cookie': cookie }
            });
        }
    } else {
        console.log(`  Actual SHA-256:   N/A (Complete failed)`);
        console.log(`  Integrity: FAIL`);
        console.log(`\nResult:`);
        console.log(`  NOT IDEMPOTENT`);
    }

    console.log(`\n======================================`);
}

runTest().catch(console.error);
