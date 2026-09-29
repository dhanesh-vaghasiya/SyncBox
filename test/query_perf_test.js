const crypto = require('crypto');

const API_BASE = 'http://localhost:8080';
const NUM_USERS = 5;
const FOLDERS_PER_USER = 5;
const FILES_PER_FOLDER = 20; // 5 * 5 * 20 = 500 files total to seed
const NUM_QUERY_ITERATIONS = 500; // How many times to hit the query endpoints per user

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

async function createFolder(cookie, name, parentId = null) {
    const url = parentId 
        ? `${API_BASE}/api/folders?name=${name}&parentId=${parentId}`
        : `${API_BASE}/api/folders?name=${name}`;
    const res = await fetch(url, { method: 'POST', headers: { 'Cookie': cookie } });
    if (!res.ok) return null;
    const data = await res.json();
    return data.id;
}

async function uploadSmallFile(cookie, folderId, fileName) {
    // 1. Session
    const sessRes = await fetch(`${API_BASE}/api/files/upload/session?originalName=${fileName}&contentType=text/plain&totalSize=1&totalChunks=1&folderId=${folderId}`, {
        method: 'POST',
        headers: { 'Cookie': cookie }
    });
    if (!sessRes.ok) return null;
    const sessionId = (await sessRes.json()).id;

    // 2. Upload Chunk
    const chunkData = new Uint8Array([65]);
    const formData = new FormData();
    formData.append('file', new Blob([chunkData], { type: 'text/plain' }), 'chunk.txt');
    formData.append('chunkIndex', 0);
    const upRes = await fetch(`${API_BASE}/api/files/upload/session/${sessionId}/part/0`, {
        method: 'PUT',
        headers: { 'Cookie': cookie },
        body: formData
    });
    if (!upRes.ok) return null;

    // 3. Complete
    await fetch(`${API_BASE}/api/files/upload/session/${sessionId}/complete?duplicateAction=KEEP_BOTH`, {
        method: 'POST',
        headers: { 'Cookie': cookie }
    });
    return true;
}

async function measureTime(promiseFn) {
    const start = performance.now();
    let success = true;
    try {
        const res = await promiseFn();
        if (!res.ok) success = false;
    } catch(e) {
        success = false;
    }
    const end = performance.now();
    return { time: end - start, success };
}

function calculateStats(times) {
    if (times.length === 0) return { avg: 0, min: 0, max: 0 };
    const sum = times.reduce((a, b) => a + b, 0);
    return {
        avg: (sum / times.length).toFixed(2),
        min: Math.min(...times).toFixed(2),
        max: Math.max(...times).toFixed(2)
    };
}

async function runTest() {
    console.log("========== SYNCBOX DATABASE QUERY PERFORMANCE TEST ==========\n");
    console.log(`Seeding database with ${NUM_USERS} users, ${FOLDERS_PER_USER} folders/user, ${FILES_PER_FOLDER} files/folder...`);
    
    const users = [];
    for (let u = 0; u < NUM_USERS; u++) {
        const username = `perf_user_${Date.now()}_${u}`;
        const password = 'password123';
        const signupData = new URLSearchParams();
        signupData.append('username', username);
        signupData.append('password', password);
        await fetch(`${API_BASE}/api/auth/signup`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: signupData.toString() });
        const cookie = await doLogin(username, password);
        
        users.push({ username, cookie, folders: [] });
    }

    // Seed data in parallel for speed
    console.log("Creating folders and uploading files (this may take a moment)...");
    const seedPromises = [];
    for (const user of users) {
        seedPromises.push((async () => {
            for (let f = 0; f < FOLDERS_PER_USER; f++) {
                const folderId = await createFolder(user.cookie, `Folder_${f}`);
                user.folders.push(folderId);
                const fileUploads = [];
                for (let i = 0; i < FILES_PER_FOLDER; i++) {
                    fileUploads.push(uploadSmallFile(user.cookie, folderId, `file_${i}.txt`));
                }
                await Promise.all(fileUploads);
            }
        })());
    }
    await Promise.all(seedPromises);
    console.log("Data seeding complete!\n");

    // Start query load testing
    console.log(`Running ${NUM_QUERY_ITERATIONS} query iterations across all users concurrently...\n`);

    const metrics = {
        fileList: { times: [], errors: 0 },
        folderList: { times: [], errors: 0 },
        checksumLookup: { times: [], errors: 0 }
    };

    const dummyHash = crypto.createHash('sha256').update(new Uint8Array([65])).digest('hex');

    const queryPromises = [];
    
    // Distribute queries evenly
    for (let i = 0; i < NUM_QUERY_ITERATIONS; i++) {
        for (const user of users) {
            // File Listing
            queryPromises.push((async () => {
                const m = await measureTime(() => fetch(`${API_BASE}/api/files/list`, { headers: { 'Cookie': user.cookie } }));
                if (m.success) metrics.fileList.times.push(m.time); else metrics.fileList.errors++;
            })());

            // Folder Listing
            queryPromises.push((async () => {
                const m = await measureTime(() => fetch(`${API_BASE}/api/folders/all`, { headers: { 'Cookie': user.cookie } }));
                if (m.success) metrics.folderList.times.push(m.time); else metrics.folderList.errors++;
            })());

            // Checksum Lookup
            queryPromises.push((async () => {
                const m = await measureTime(() => fetch(`${API_BASE}/api/files/check?checksum=${dummyHash}`, { headers: { 'Cookie': user.cookie } }));
                if (m.success) metrics.checksumLookup.times.push(m.time); else metrics.checksumLookup.errors++;
            })());
        }
    }

    await Promise.all(queryPromises);

    console.log("--- TEST RESULTS ---\n");

    const printMetric = (name, m) => {
        const stats = calculateStats(m.times);
        console.log(`Endpoint: ${name}`);
        console.log(`  Total Successful: ${m.times.length}`);
        console.log(`  Total Failed:     ${m.errors}`);
        console.log(`  Min Time:         ${stats.min} ms`);
        console.log(`  Max Time:         ${stats.max} ms`);
        console.log(`  Average Time:     ${stats.avg} ms\n`);
    };

    printMetric("File Listing (/api/files/list)", metrics.fileList);
    printMetric("Folder Listing (/api/folders/all)", metrics.folderList);
    printMetric("Duplicate/Checksum Lookup (/api/files/check)", metrics.checksumLookup);

    console.log("Note: File Search endpoint is not implemented on the backend yet, so it was excluded from the test.");
    console.log("===========================================================");
}

runTest().catch(console.error);
