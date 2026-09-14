self.addEventListener('message', async (e) => {
    const file = e.data;
    
    try {
        const CHUNK_SIZE = 1048576; // 1MB
        const L = file.size;
        let bufferToHash;

        if (L <= 3 * CHUNK_SIZE) {
            // Hash the entire file if it's small
            bufferToHash = await file.arrayBuffer();
        } else {
            // Smart Sampling: First 1MB + Middle 1MB + Last 1MB
            const firstSlice = file.slice(0, CHUNK_SIZE);
            const mid = Math.floor(L / 2);
            const middleSlice = file.slice(mid - (CHUNK_SIZE / 2), mid + (CHUNK_SIZE / 2));
            const lastSlice = file.slice(L - CHUNK_SIZE, L);

            const [firstBuf, midBuf, lastBuf] = await Promise.all([
                firstSlice.arrayBuffer(),
                middleSlice.arrayBuffer(),
                lastSlice.arrayBuffer()
            ]);

            // Combine into one 3MB buffer
            bufferToHash = new Uint8Array(3 * CHUNK_SIZE);
            bufferToHash.set(new Uint8Array(firstBuf), 0);
            bufferToHash.set(new Uint8Array(midBuf), CHUNK_SIZE);
            bufferToHash.set(new Uint8Array(lastBuf), 2 * CHUNK_SIZE);
        }

        // Native hardware-accelerated SHA-256
        const hashBuffer = await crypto.subtle.digest('SHA-256', bufferToHash);
        
        // Convert to hex string
        const hashArray = Array.from(new Uint8Array(hashBuffer));
        const hashHex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
        
        self.postMessage({ success: true, hash: hashHex });
    } catch (err) {
        self.postMessage({ success: false, error: err.message });
    }
});
