const crypto = require('crypto');

const API_BASE = 'http://localhost:8080';
const FILE_SIZE = 2 * 1024 * 1024;
const CHUNK_SIZE = 2 * 1024 * 1024;

async function runTest() {
    console.log("Starting simple concurrent idempotency test...");
    const username = `simple_idemp_${Date.now()}`;
    const password = 'password123';
    const fileName = `simple_test.txt`;

    // Auth
    const signupData = new URLSearchParams();
    signupData.append('username', username);
    signupData.append('password', password);
    await fetch(`${API_BASE}/api/auth/signup`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: signupData.toString() });

    const loginData = new URLSearchParams();
    loginData.append('username', username);
    loginData.append('password', password);
    const loginRes = await fetch(`${API_BASE}/login`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: loginData.toString(), redirect: 'manual' });
    
    let cookie = '';
    for (const c of loginRes.headers.getSetCookie()) {
        if (c.startsWith('JSESSIONID=')) cookie = c.split(';')[0];
    }

    // Create session
    const sessRes = await fetch(`${API_BASE}/api/files/upload/session?originalName=${fileName}&contentType=text/plain&totalSize=${FILE_SIZE}&totalChunks=1`, { method: 'POST', headers: { 'Cookie': cookie } });
    const sessionId = (await sessRes.json()).id;

    // Generate dummy data
    const chunkData = new Uint8Array(CHUNK_SIZE);
    chunkData.fill(66);
    
    const expectedHashFn = crypto.createHash('sha256');
    expectedHashFn.update(chunkData);
    const expectedHash = expectedHashFn.digest('hex');

    // Fire 10 requests concurrently
    console.log("Firing 10 concurrent requests for chunk 0...");
    const promises = [];
    for(let i=0; i<10; i++) {
        const formData = new FormData();
        formData.append('file', new Blob([chunkData], { type: 'text/plain' }), 'chunk.txt');
        formData.append('chunkIndex', 0);
        promises.push(fetch(`${API_BASE}/api/files/upload/session/${sessionId}/part/0`, { method: 'PUT', headers: { 'Cookie': cookie }, body: formData }));
    }
    
    const responses = await Promise.all(promises);
    const allSuccess = responses.every(r => r.ok);
    console.log(`Concurrent Upload Status: ${allSuccess ? 'PASS (All HTTP 200)' : 'FAIL (Some requests rejected)'}`);

    // Complete upload
    await fetch(`${API_BASE}/api/files/upload/session/${sessionId}/complete?duplicateAction=KEEP_BOTH`, { method: 'POST', headers: { 'Cookie': cookie } });

    console.log("Waiting for file assembly...");
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

    if (!storedName) {
        console.log("Result: FAIL (File not found in list)");
        return;
    }

    // Download & Verify
    const dlRes = await fetch(`${API_BASE}/api/files/download/${storedName}`, { headers: { 'Cookie': cookie } });
    const fileHashFn = crypto.createHash('sha256');
    for await (const chunk of dlRes.body) fileHashFn.update(chunk);
    
    const downloadedHash = fileHashFn.digest('hex');
    const integrityPass = (downloadedHash === expectedHash);

    console.log(`\nFinal file:`);
    console.log(`  Expected SHA-256: ${expectedHash}`);
    console.log(`  Actual SHA-256:   ${downloadedHash}`);
    console.log(`  Integrity: ${integrityPass ? 'PASS' : 'FAIL'}`);

    console.log(`\nResult: ${allSuccess && integrityPass ? 'PASS' : 'FAIL'}`);

    // Cleanup
    await fetch(`${API_BASE}/api/files/${storedName}/permanent`, { method: 'DELETE', headers: { 'Cookie': cookie } });
}

runTest().catch(console.error);
