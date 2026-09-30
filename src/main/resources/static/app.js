/**
 * Chitthi (चिठ्ठी) - Interactive Web Reader & Live Pipeline Dashboard
 * Client application logic for real-time SSE streams, dual-voice audio player,
 * side-by-side reading, and Indic trigram archive search.
 */

(() => {
    'use strict';

    // Application State
    const state = {
        activeDoc: null,
        currentPageIndex: 0,
        currentAudioTrack: 'en',
        eventSource: null,
        ownerId: 'default',
        searchDebounceTimer: null
    };

    // DOM Elements Cache
    const el = {
        // Navigation & Owner
        ownerIdInput: document.getElementById('ownerIdInput'),
        btnToggleArchive: document.getElementById('btnToggleArchive'),
        btnNewUpload: document.getElementById('btnNewUpload'),
        archiveDrawer: document.getElementById('archiveDrawer'),
        btnCloseArchive: document.getElementById('btnCloseArchive'),
        drawerBackdrop: document.getElementById('drawerBackdrop'),

        // Upload Form
        uploadForm: document.getElementById('uploadForm'),
        dropZone: document.getElementById('dropZone'),
        fileInput: document.getElementById('fileInput'),
        fileNameDisplay: document.getElementById('fileNameDisplay'),
        docTitle: document.getElementById('docTitle'),
        docLanguage: document.getElementById('docLanguage'),
        docYear: document.getElementById('docYear'),
        btnSubmitUpload: document.getElementById('btnSubmitUpload'),
        btnSubmitText: document.getElementById('btnSubmitText'),
        uploadSpinner: document.getElementById('uploadSpinner'),

        // Tracker & Pipeline
        activeDocId: document.getElementById('activeDocId'),
        activeStatusPill: document.getElementById('activeStatusPill'),
        sseIndicator: document.getElementById('sseIndicator'),
        sseStatusText: document.getElementById('sseStatusText'),
        pipelineProgressFill: document.getElementById('pipelineProgressFill'),
        progressPercentText: document.getElementById('progressPercentText'),
        progressEtaText: document.getElementById('progressEtaText'),
        eventLogList: document.getElementById('eventLogList'),
        logCountBadge: document.getElementById('logCountBadge'),
        logToggleBtn: document.getElementById('logToggleBtn'),

        // Stages
        stepUpload: document.getElementById('step-UPLOAD'),
        stepOcr: document.getElementById('step-OCR'),
        stepTranslate: document.getElementById('step-TRANSLATE'),
        stepTts: document.getElementById('step-TTS'),
        stepAssemble: document.getElementById('step-ASSEMBLE'),

        // Audio Player
        nativeAudio: document.getElementById('nativeAudio'),
        btnPlayPause: document.getElementById('btnPlayPause'),
        iconPlay: document.querySelector('.icon-play'),
        iconPause: document.querySelector('.icon-pause'),
        audioTimeline: document.getElementById('audioTimeline'),
        audioCurrentTime: document.getElementById('audioCurrentTime'),
        audioDuration: document.getElementById('audioDuration'),
        audioVolume: document.getElementById('audioVolume'),
        btnDownloadAudio: document.getElementById('btnDownloadAudio'),
        waveAnimation: document.getElementById('waveAnimation'),
        playerAudioTitle: document.getElementById('playerAudioTitle'),
        playerAudioTrack: document.getElementById('playerAudioTrack'),
        btnVoiceEn: document.getElementById('btnVoiceEn'),
        btnVoiceOrig: document.getElementById('btnVoiceOrig'),
        voiceOrigLabel: document.getElementById('voiceOrigLabel'),

        // Reader & Pager
        splitReader: document.getElementById('splitReader'),
        btnPrevPage: document.getElementById('btnPrevPage'),
        btnNextPage: document.getElementById('btnNextPage'),
        pageDisplay: document.getElementById('pageDisplay'),
        btnViewSplit: document.getElementById('btnViewSplit'),
        btnViewScan: document.getElementById('btnViewScan'),
        btnViewText: document.getElementById('btnViewText'),
        scanPlaceholder: document.getElementById('scanPlaceholder'),
        scannedImage: document.getElementById('scannedImage'),
        origPageMeta: document.getElementById('origPageMeta'),
        indicTextDisplay: document.getElementById('indicTextDisplay'),
        btnToggleEdit: document.getElementById('btnToggleEdit'),
        btnEditTextLabel: document.getElementById('btnEditTextLabel'),
        indicEditContainer: document.getElementById('indicEditContainer'),
        indicTextEditInput: document.getElementById('indicTextEditInput'),
        btnCancelEdit: document.getElementById('btnCancelEdit'),
        btnSaveEdit: document.getElementById('btnSaveEdit'),
        btnSaveEditText: document.getElementById('btnSaveEditText'),
        editSpinner: document.getElementById('editSpinner'),
        transPageMeta: document.getElementById('transPageMeta'),
        translationPlaceholder: document.getElementById('translationPlaceholder'),
        englishTextDisplay: document.getElementById('englishTextDisplay'),

        // Archive & Search
        archiveSearchInput: document.getElementById('archiveSearchInput'),
        drawerListHeader: document.getElementById('drawerListHeader'),
        archiveLetterList: document.getElementById('archiveLetterList'),
        toastContainer: document.getElementById('toastContainer'),

        // Usage Ledger & Observability
        btnToggleUsage: document.getElementById('btnToggleUsage'),
        usageDrawer: document.getElementById('usageDrawer'),
        btnCloseUsage: document.getElementById('btnCloseUsage'),
        btnRefreshUsage: document.getElementById('btnRefreshUsage'),
        quotaBadge: document.getElementById('quotaBadge'),
        usageWordsText: document.getElementById('usageWordsText'),
        usageWordsPercent: document.getElementById('usageWordsPercent'),
        usageProgressBar: document.getElementById('usageProgressBar'),
        usageTotalSpend: document.getElementById('usageTotalSpend'),
        usageTotalCalls: document.getElementById('usageTotalCalls'),
        usageCacheHits: document.getElementById('usageCacheHits'),
        usageEndpointBody: document.getElementById('usageEndpointBody')
    };

    // Stage Elements Map
    const stageSteps = {
        'UPLOAD': el.stepUpload,
        'OCR': el.stepOcr,
        'TRANSLATE': el.stepTranslate,
        'TTS': el.stepTts,
        'ASSEMBLE': el.stepAssemble
    };

    // Stage Weighting for Smooth Progress Calculation
    const stageWeights = {
        'UPLOAD': 15,
        'OCR': 40,
        'TRANSLATE': 65,
        'TTS': 85,
        'ASSEMBLE': 100
    };

    /* ==========================================================================
       Initialization
       ========================================================================== */
    function init() {
        setupOwner();
        setupDragAndDrop();
        setupUploadForm();
        setupAudioPlayer();
        setupReaderControls();
        setupArchiveDrawer();
        setupUsageDrawer();
        loadRecentLetters();
        loadUsageData();
    }

    function setupOwner() {
        state.ownerId = el.ownerIdInput.value.trim() || 'default';
        el.ownerIdInput.addEventListener('change', () => {
            state.ownerId = el.ownerIdInput.value.trim() || 'default';
            loadRecentLetters();
            loadUsageData();
            showToast(`Switched workspace to owner: ${state.ownerId}`, 'info');
        });
    }

    /* ==========================================================================
       Drag & Drop and Upload Form Handling
       ========================================================================== */
    function setupDragAndDrop() {
        const dropZone = el.dropZone;
        const fileInput = el.fileInput;

        ['dragenter', 'dragover'].forEach(eventName => {
            dropZone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                dropZone.classList.add('dragover');
            });
        });

        ['dragleave', 'drop'].forEach(eventName => {
            dropZone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                dropZone.classList.remove('dragover');
            });
        });

        dropZone.addEventListener('drop', (e) => {
            const files = e.dataTransfer.files;
            if (files && files.length > 0) {
                fileInput.files = files;
                updateFileDisplay(files[0]);
            }
        });

        dropZone.addEventListener('click', () => {
            fileInput.click();
        });

        fileInput.addEventListener('change', () => {
            if (fileInput.files.length > 0) {
                updateFileDisplay(fileInput.files[0]);
            }
        });
    }

    function updateFileDisplay(file) {
        const sizeMb = (file.size / (1024 * 1024)).toFixed(2);
        el.fileNameDisplay.textContent = `Selected: ${file.name} (${sizeMb} MB)`;
        el.fileNameDisplay.style.color = 'var(--accent-gold)';

        if (!el.docTitle.value) {
            const cleanName = file.name.replace(/\.[^/.]+$/, '').replace(/[-_]/g, ' ');
            el.docTitle.value = cleanName.charAt(0).toUpperCase() + cleanName.slice(1);
        }
    }

    function setupUploadForm() {
        el.uploadForm.addEventListener('submit', async (e) => {
            e.preventDefault();

            if (!el.fileInput.files || el.fileInput.files.length === 0) {
                showToast('Please select a PDF or image file first', 'error');
                return;
            }

            const file = el.fileInput.files[0];
            const title = el.docTitle.value.trim();
            const language = el.docLanguage.value;
            const year = el.docYear.value.trim();

            const formData = new FormData();
            formData.append('file', file);
            formData.append('title', title);
            formData.append('language', language);
            formData.append('ownerId', state.ownerId);
            if (year) {
                formData.append('year', year);
            }

            setUploadLoading(true);
            resetTrackerUI(title);

            try {
                const response = await fetch('/api/documents', {
                    method: 'POST',
                    body: formData
                });

                if (!response.ok) {
                    const err = await response.text();
                    throw new Error(`Upload failed (${response.status}): ${err}`);
                }

                const docData = await response.json();
                showToast(`Letter uploaded successfully! Digitize pipeline started.`, 'success');

                // Connect SSE stream
                connectEventStream(docData.documentId, docData.title, language);

            } catch (err) {
                console.error(err);
                showToast(err.message || 'Failed to upload document', 'error');
                updateTrackerStatus('FAILED');
            } finally {
                setUploadLoading(false);
            }
        });

        el.btnNewUpload.addEventListener('click', () => {
            window.scrollTo({ top: 0, behavior: 'smooth' });
            el.docTitle.focus();
        });
    }

    function setUploadLoading(isLoading) {
        if (isLoading) {
            el.btnSubmitUpload.disabled = true;
            el.btnSubmitText.textContent = 'Uploading & Chunking...';
            el.uploadSpinner.classList.remove('hidden');
        } else {
            el.btnSubmitUpload.disabled = false;
            el.btnSubmitText.textContent = 'Start Ingestion & Digitization Pipeline';
            el.uploadSpinner.classList.add('hidden');
        }
    }

    /* ==========================================================================
       SSE Real-Time Progress Stream
       ========================================================================== */
    function connectEventStream(documentId, title, language) {
        if (state.eventSource) {
            state.eventSource.close();
            state.eventSource = null;
        }

        // Set active doc info
        el.activeDocId.textContent = `ID: ${documentId.slice(0, 8)}...`;
        el.activeDocId.title = documentId;
        updateTrackerStatus('PROCESSING');
        setSseStatus('connected', 'Live Stream Active');

        // Pre-configure original voice label
        const langNames = {
            'hi': 'Hindi (हिन्दी)',
            'bn': 'Bengali (বাংলা)',
            'gu': 'Gujarati (ગુજરાતી)',
            'kn': 'Kannada (ಕನ್ನಡ)',
            'ml': 'Malayalam (മലയാളം)',
            'mr': 'Marathi (मराठी)',
            'od': 'Odia (ଓଡ଼ିଆ)',
            'pa': 'Punjabi (ਪੰਜਾਬੀ)',
            'ta': 'Tamil (தமிழ்)',
            'te': 'Telugu (తెలుగు)'
        };
        el.voiceOrigLabel.textContent = langNames[language] || 'Original Indic';

        // Connect SSE endpoint
        const sseUrl = `/api/documents/${documentId}/events`;
        const es = new EventSource(sseUrl);
        state.eventSource = es;

        let eventCount = 0;

        es.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                eventCount++;
                el.logCountBadge.textContent = `${eventCount} events`;
                handlePipelineEvent(data, documentId, title);
            } catch (err) {
                console.error('Failed to parse SSE event data', err, event.data);
            }
        };

        es.onerror = (err) => {
            console.warn('SSE connection warning or stream closed:', err);
            // Don't auto-fail immediately; check document status after a brief pause
            setTimeout(() => verifyDocumentComplete(documentId), 2000);
        };
    }

    function handlePipelineEvent(evt, documentId, title) {
        const { stage, status, pageNo, totalPages, progressPercent, message, timestamp } = evt;

        // Add log entry
        appendLogItem(timestamp, stage, message);

        // Update progress bar
        let percent = progressPercent;
        if (!percent || percent <= 0) {
            percent = stageWeights[stage] || 10;
        }
        updateProgressBar(percent, message);

        // Update Stage Step Visuals
        const stepEl = stageSteps[stage];
        if (stepEl) {
            const stateEl = stepEl.querySelector('.stage-state');

            if (status === 'COMPLETED') {
                stepEl.className = 'stage-step step-completed';
                stateEl.className = 'stage-state state-completed';
                stateEl.textContent = pageNo && totalPages ? `Done (p.${pageNo}/${totalPages})` : 'Done';
            } else if (status === 'RUNNING' || status === 'SUBMITTED') {
                stepEl.className = 'stage-step step-running';
                stateEl.className = 'stage-state state-running';
                stateEl.textContent = pageNo && totalPages ? `Page ${pageNo}/${totalPages}` : 'Running';
            } else if (status === 'FAILED') {
                stepEl.className = 'stage-step step-failed';
                stateEl.className = 'stage-state state-failed';
                stateEl.textContent = 'Failed';
                updateTrackerStatus('FAILED');
                showToast(`Stage ${stage} failed: ${message}`, 'error');
            }
        }

        // Daily processing cap warning
        if (stage === 'CAP_EXCEEDED' || status === 'WARNING') {
            updateTrackerStatus('PARTIAL');
            showToast(`Daily processing ceiling: ${message}`, 'error', 8000);
            loadUsageData();
        }

        // Check if master assembly completed
        if (stage === 'ASSEMBLE' && status === 'COMPLETED') {
            onPipelineCompleted(documentId);
            loadUsageData();
        }
    }

    async function onPipelineCompleted(documentId) {
        updateProgressBar(100, 'Processing complete! Ready to read & listen.');
        updateTrackerStatus('COMPLETE');
        setSseStatus('complete', 'Stream Closed (Complete)');

        if (state.eventSource) {
            state.eventSource.close();
            state.eventSource = null;
        }

        showToast('Digitization complete! Loading letter pages and dual-voice audio...', 'success');

        // Fetch full document details and load reader
        await loadDocumentDetails(documentId);
        loadRecentLetters();
    }

    async function verifyDocumentComplete(documentId) {
        try {
            const res = await fetch(`/api/documents/${documentId}`);
            if (res.ok) {
                const doc = await res.json();
                if (doc.status === 'COMPLETE') {
                    onPipelineCompleted(documentId);
                }
            }
        } catch (e) {
            // Ignore background check failure
        }
    }

    function resetTrackerUI(title) {
        el.activeDocId.textContent = 'Initializing...';
        updateTrackerStatus('PENDING');
        updateProgressBar(5, 'Submitting document to ingestion service...');
        el.eventLogList.innerHTML = '';
        el.logCountBadge.textContent = '0 events';

        Object.values(stageSteps).forEach(step => {
            step.className = 'stage-step';
            const stateEl = step.querySelector('.stage-state');
            stateEl.className = 'stage-state state-waiting';
            stateEl.textContent = 'Waiting';
        });

        // Reader reset
        el.scanPlaceholder.classList.remove('hidden');
        el.scannedImage.classList.add('hidden');
        el.scannedImage.src = '';
        el.indicTextDisplay.textContent = 'OCR transcription will appear here once processed.';
        el.translationPlaceholder.classList.remove('hidden');
        el.englishTextDisplay.classList.add('hidden');
        el.englishTextDisplay.innerHTML = '';

        // Audio reset
        el.btnPlayPause.disabled = true;
        el.audioTimeline.disabled = true;
        el.playerAudioTitle.textContent = title || 'Natural Audio Reader';
        el.playerAudioTrack.textContent = 'Processing speech synthesis...';
    }

    function updateTrackerStatus(status) {
        el.activeStatusPill.className = `status-pill status-${status.toLowerCase()}`;
        el.activeStatusPill.textContent = status;
    }

    function setSseStatus(stateClass, label) {
        const dot = el.sseIndicator.querySelector('.pulse-dot');
        dot.className = `pulse-dot pulse-${stateClass}`;
        el.sseStatusText.textContent = label;
    }

    function updateProgressBar(percent, etaText) {
        const clamped = Math.min(100, Math.max(0, percent));
        el.pipelineProgressFill.style.width = `${clamped}%`;
        el.progressPercentText.textContent = `${Math.round(clamped)}% Completed`;
        if (etaText) {
            el.progressEtaText.textContent = etaText;
        }
    }

    function appendLogItem(timestamp, stage, message) {
        const timeStr = timestamp ? new Date(timestamp).toLocaleTimeString() : new Date().toLocaleTimeString();
        const div = document.createElement('div');
        div.className = 'log-item';
        div.innerHTML = `
            <span class="log-time">[${timeStr}]</span>
            <span class="log-stage">${stage}:</span>
            <span class="log-msg">${escapeHtml(message)}</span>
        `;
        el.eventLogList.appendChild(div);
        el.eventLogList.scrollTop = el.eventLogList.scrollHeight;
    }

    /* ==========================================================================
       Interactive Reader & Pager
       ========================================================================== */
    async function loadDocumentDetails(documentId) {
        try {
            const res = await fetch(`/api/documents/${documentId}`);
            if (!res.ok) throw new Error('Document details not found');
            const doc = await res.json();
            state.activeDoc = doc;
            state.currentPageIndex = 0;

            // Update UI headers
            el.playerAudioTitle.textContent = doc.title;
            renderCurrentPage();

            // Load audio track
            loadAudioTrack(documentId, state.currentAudioTrack);

            // Scroll reader into view
            el.readerCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

        } catch (err) {
            console.error('Error fetching document:', err);
            showToast('Failed to load letter details', 'error');
        }
    }

    function renderCurrentPage() {
        const doc = state.activeDoc;
        if (!doc || !doc.pages || doc.pages.length === 0) return;

        closeEditMode();

        const totalPages = doc.pages.length;
        const page = doc.pages[state.currentPageIndex];

        // Pager buttons
        el.pageDisplay.textContent = `Page ${page.pageNo} of ${totalPages}`;
        el.btnPrevPage.disabled = state.currentPageIndex === 0;
        el.btnNextPage.disabled = state.currentPageIndex >= totalPages - 1;

        if (page.edited) {
            el.origPageMeta.innerHTML = `Page ${page.pageNo} (${doc.language.toUpperCase()}) <span class="badge badge-amber" style="margin-left:6px; font-size:10px;">Edited</span>`;
        } else {
            el.origPageMeta.textContent = `Page ${page.pageNo} (${doc.language.toUpperCase()})`;
        }
        el.transPageMeta.textContent = `Page ${page.pageNo} English Translation`;

        // Scanned Image
        if (page.imageUrl) {
            el.scanPlaceholder.classList.add('hidden');
            el.scannedImage.classList.remove('hidden');
            el.scannedImage.src = page.imageUrl;
        } else {
            el.scanPlaceholder.classList.remove('hidden');
            el.scannedImage.classList.add('hidden');
        }

        // Indic OCR Text
        el.indicTextDisplay.textContent = page.originalText || 'No Indic text detected for this page.';

        // English Translation
        if (page.translatedText && page.translatedText.trim()) {
            el.translationPlaceholder.classList.add('hidden');
            el.englishTextDisplay.classList.remove('hidden');

            // Format into readable paragraphs
            const paragraphs = page.translatedText.split(/\n\n+/).filter(p => p.trim());
            el.englishTextDisplay.innerHTML = paragraphs.map(p => `<p>${escapeHtml(p)}</p>`).join('');
        } else {
            el.translationPlaceholder.classList.remove('hidden');
            el.englishTextDisplay.classList.add('hidden');
            el.englishTextDisplay.innerHTML = '';
        }
    }

    function setupReaderControls() {
        el.btnPrevPage.addEventListener('click', () => {
            if (state.currentPageIndex > 0) {
                state.currentPageIndex--;
                renderCurrentPage();
            }
        });

        el.btnNextPage.addEventListener('click', () => {
            if (state.activeDoc && state.currentPageIndex < state.activeDoc.pages.length - 1) {
                state.currentPageIndex++;
                renderCurrentPage();
            }
        });

        // View Mode Toggles
        const viewButtons = [
            { btn: el.btnViewSplit, cls: '' },
            { btn: el.btnViewScan, cls: 'view-scan-only' },
            { btn: el.btnViewText, cls: 'view-text-only' }
        ];

        viewButtons.forEach(({ btn, cls }) => {
            btn.addEventListener('click', () => {
                viewButtons.forEach(b => b.btn.classList.remove('active'));
                btn.classList.add('active');
                el.splitReader.className = 'split-reader-container ' + cls;
            });
        });

        // Event Log Collapsible Toggle
        el.logToggleBtn.addEventListener('click', () => {
            const isHidden = el.eventLogList.style.display === 'none';
            el.eventLogList.style.display = isHidden ? 'flex' : 'none';
        });

        // Setup Archivist Edit Flow
        setupEditMode();
    }

    function setupEditMode() {
        if (!el.btnToggleEdit) return;

        el.btnToggleEdit.addEventListener('click', () => {
            const doc = state.activeDoc;
            if (!doc || !doc.pages || doc.pages.length === 0) {
                showToast('Please open or upload a letter first', 'info');
                return;
            }

            const page = doc.pages[state.currentPageIndex];
            const isEditing = !el.indicEditContainer.classList.contains('hidden');

            if (isEditing) {
                closeEditMode();
            } else {
                openEditMode(page);
            }
        });

        if (el.btnCancelEdit) {
            el.btnCancelEdit.addEventListener('click', () => {
                closeEditMode();
            });
        }

        if (el.btnSaveEdit) {
            el.btnSaveEdit.addEventListener('click', async () => {
                const doc = state.activeDoc;
                if (!doc || !doc.pages || doc.pages.length === 0) return;

                const page = doc.pages[state.currentPageIndex];
                const newText = el.indicTextEditInput.value.trim();

                if (!newText) {
                    showToast('Text content cannot be empty', 'error');
                    el.indicTextEditInput.focus();
                    return;
                }

                if (newText === (page.originalText || '').trim()) {
                    showToast('No changes detected in transcription', 'info');
                    closeEditMode();
                    return;
                }

                // Begin save & partial regeneration
                el.btnSaveEdit.disabled = true;
                el.btnCancelEdit.disabled = true;
                el.editSpinner.classList.remove('hidden');
                el.btnSaveEditText.textContent = 'Saving...';

                try {
                    const res = await fetch(`/api/documents/${doc.id}/pages/${page.pageNo}/text`, {
                        method: 'PUT',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ text: newText })
                    });

                    if (!res.ok) {
                        const errData = await res.json().catch(() => ({}));
                        throw new Error(errData.message || `HTTP ${res.status}`);
                    }

                    const updatedDoc = await res.json();
                    state.activeDoc = updatedDoc;

                    closeEditMode();
                    renderCurrentPage();

                    // Connect to SSE stream to monitor live regeneration
                    connectSse(doc.id, doc.title);
                    updateTrackerStatus('PROCESSING');

                    showToast(`Page ${page.pageNo} updated! Regenerating translation & speech...`, 'success');
                } catch (err) {
                    console.error('Failed to save page edit:', err);
                    showToast(`Failed to update page: ${err.message}`, 'error');
                } finally {
                    el.btnSaveEdit.disabled = false;
                    el.btnCancelEdit.disabled = false;
                    el.editSpinner.classList.add('hidden');
                    el.btnSaveEditText.textContent = 'Save & Regenerate Audio';
                }
            });
        }
    }

    function openEditMode(page) {
        if (!el.indicEditContainer) return;
        el.indicTextEditInput.value = page.originalText || '';
        el.indicEditContainer.classList.remove('hidden');
        el.indicTextDisplay.classList.add('hidden');
        if (el.btnEditTextLabel) el.btnEditTextLabel.textContent = 'Cancel Edit';
        el.indicTextEditInput.focus();
    }

    function closeEditMode() {
        if (!el.indicEditContainer) return;
        el.indicEditContainer.classList.add('hidden');
        el.indicTextDisplay.classList.remove('hidden');
        if (el.btnEditTextLabel) el.btnEditTextLabel.textContent = 'Edit Text';
    }

    /* ==========================================================================
       Dual-Voice Audio Studio Player
       ========================================================================== */
    function setupAudioPlayer() {
        const audio = el.nativeAudio;

        // Play/Pause Button
        el.btnPlayPause.addEventListener('click', () => {
            if (audio.paused) {
                audio.play().catch(e => console.error('Audio play failed', e));
            } else {
                audio.pause();
            }
        });

        // Audio Event Listeners
        audio.addEventListener('play', () => {
            el.iconPlay.classList.add('hidden');
            el.iconPause.classList.remove('hidden');
            el.waveAnimation.classList.add('playing');
        });

        audio.addEventListener('pause', () => {
            el.iconPlay.classList.remove('hidden');
            el.iconPause.classList.add('hidden');
            el.waveAnimation.classList.remove('playing');
        });

        audio.addEventListener('ended', () => {
            el.iconPlay.classList.remove('hidden');
            el.iconPause.classList.add('hidden');
            el.waveAnimation.classList.remove('playing');
            el.audioTimeline.value = 0;
            el.audioCurrentTime.textContent = '00:00';
        });

        audio.addEventListener('timeupdate', () => {
            if (!isNaN(audio.duration) && audio.duration > 0) {
                el.audioTimeline.value = (audio.currentTime / audio.duration) * 100;
                el.audioCurrentTime.textContent = formatTime(audio.currentTime);
            }
        });

        audio.addEventListener('loadedmetadata', () => {
            el.audioDuration.textContent = formatTime(audio.duration);
            el.btnPlayPause.disabled = false;
            el.audioTimeline.disabled = false;
        });

        // Timeline Scrubber
        el.audioTimeline.addEventListener('input', () => {
            if (!isNaN(audio.duration) && audio.duration > 0) {
                const targetTime = (el.audioTimeline.value / 100) * audio.duration;
                audio.currentTime = targetTime;
            }
        });

        // Volume Slider
        el.audioVolume.addEventListener('input', () => {
            audio.volume = el.audioVolume.value;
        });

        // Dual-Voice Buttons
        el.btnVoiceEn.addEventListener('click', () => {
            switchAudioTrack('en');
        });

        el.btnVoiceOrig.addEventListener('click', () => {
            switchAudioTrack('orig');
        });
    }

    function switchAudioTrack(track) {
        if (state.currentAudioTrack === track) return;
        state.currentAudioTrack = track;

        if (track === 'en') {
            el.btnVoiceEn.classList.add('active');
            el.btnVoiceOrig.classList.remove('active');
        } else {
            el.btnVoiceOrig.classList.add('active');
            el.btnVoiceEn.classList.remove('active');
        }

        if (state.activeDoc) {
            loadAudioTrack(state.activeDoc.id, track);
        }
    }

    async function loadAudioTrack(documentId, lang) {
        const wasPlaying = !el.nativeAudio.paused;
        const currentPos = el.nativeAudio.currentTime;

        const trackLabel = lang === 'en' ? 'English Voice (Sarvam Bulbul v3)' : 'Original Indic Voice (Sarvam Bulbul)';
        el.playerAudioTrack.textContent = `Loading ${trackLabel}...`;

        try {
            const res = await fetch(`/api/documents/${documentId}/audio?lang=${lang}`);
            if (!res.ok) throw new Error('Audio track link not available');

            const data = await res.json();
            el.nativeAudio.src = data.audioUrl;
            el.btnDownloadAudio.href = data.audioUrl;
            el.playerAudioTrack.textContent = trackLabel;

            el.nativeAudio.load();
            if (wasPlaying) {
                el.nativeAudio.currentTime = currentPos;
                el.nativeAudio.play().catch(() => {});
            }
        } catch (err) {
            console.warn(`Audio track for ${lang} not found or still processing`, err);
            el.playerAudioTrack.textContent = `${trackLabel} (not available)`;
            el.btnPlayPause.disabled = true;
        }
    }

    function formatTime(seconds) {
        if (isNaN(seconds) || seconds < 0) return '00:00';
        const m = Math.floor(seconds / 60);
        const s = Math.floor(seconds % 60);
        return `${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
    }

    /* ==========================================================================
       Archive Drawer & Indic Trigram Search
       ========================================================================== */
    function setupArchiveDrawer() {
        el.btnToggleArchive.addEventListener('click', () => {
            openArchiveDrawer();
        });

        el.btnCloseArchive.addEventListener('click', () => {
            closeArchiveDrawer();
        });

        el.drawerBackdrop.addEventListener('click', () => {
            closeArchiveDrawer();
            closeUsageDrawer();
        });

        // Search Input
        el.archiveSearchInput.addEventListener('input', () => {
            clearTimeout(state.searchDebounceTimer);
            state.searchDebounceTimer = setTimeout(() => {
                performSearch();
            }, 300);
        });

        // Search Mode Radio Change
        document.querySelectorAll('input[name="searchMode"]').forEach(radio => {
            radio.addEventListener('change', () => {
                performSearch();
            });
        });
    }

    function openArchiveDrawer() {
        closeUsageDrawer();
        el.archiveDrawer.classList.add('open');
        el.drawerBackdrop.classList.remove('hidden');
        el.archiveSearchInput.focus();
    }

    function closeArchiveDrawer() {
        el.archiveDrawer.classList.remove('open');
        if (!el.usageDrawer.classList.contains('open')) {
            el.drawerBackdrop.classList.add('hidden');
        }
    }

    /* ==========================================================================
       Usage Ledger & Observability Drawer
       ========================================================================== */
    function setupUsageDrawer() {
        if (el.btnToggleUsage) {
            el.btnToggleUsage.addEventListener('click', () => {
                openUsageDrawer();
            });
        }

        if (el.btnCloseUsage) {
            el.btnCloseUsage.addEventListener('click', () => {
                closeUsageDrawer();
            });
        }

        if (el.btnRefreshUsage) {
            el.btnRefreshUsage.addEventListener('click', () => {
                loadUsageData();
                showToast('Usage ledger and metrics refreshed', 'info');
            });
        }
    }

    function openUsageDrawer() {
        closeArchiveDrawer();
        el.usageDrawer.classList.add('open');
        el.drawerBackdrop.classList.remove('hidden');
        loadUsageData();
    }

    function closeUsageDrawer() {
        el.usageDrawer.classList.remove('open');
        if (!el.archiveDrawer.classList.contains('open')) {
            el.drawerBackdrop.classList.add('hidden');
        }
    }

    async function loadUsageData() {
        try {
            const docParam = state.activeDoc && state.activeDoc.documentId 
                ? `&documentId=${encodeURIComponent(state.activeDoc.documentId)}` 
                : '';
            const res = await fetch(`/api/usage?ownerId=${encodeURIComponent(state.ownerId)}${docParam}`);
            if (!res.ok) throw new Error(`Usage API responded with status ${res.status}`);
            const data = await res.json();
            renderUsageData(data);
        } catch (err) {
            console.error('Error loading usage data:', err);
        }
    }

    function renderUsageData(data) {
        if (!data) return;

        // 1. Daily Word Cap (FR15)
        const processed = data.dailyWordsProcessed || 0;
        const cap = data.dailyWordCap || 7000;
        const pct = Math.min(100, Math.round((processed / cap) * 100));

        el.usageWordsText.textContent = `${processed.toLocaleString()} / ${cap.toLocaleString()}`;
        el.usageWordsPercent.textContent = `${pct}%`;
        el.usageProgressBar.style.width = `${pct}%`;

        el.usageProgressBar.className = 'quota-progress-bar';
        if (data.dailyCapExceeded || pct >= 100) {
            el.usageProgressBar.classList.add('quota-danger');
            el.quotaBadge.className = 'badge badge-rose';
            el.quotaBadge.textContent = 'Ceiling Exceeded';
        } else if (pct >= 70) {
            el.usageProgressBar.classList.add('quota-warn');
            el.quotaBadge.className = 'badge badge-amber';
            el.quotaBadge.textContent = 'Approaching Ceiling';
        } else {
            el.quotaBadge.className = 'badge badge-emerald';
            el.quotaBadge.textContent = 'Within Limit';
        }

        // 2. Spend & Ledger (FR10)
        const spend = typeof data.totalSpendInr === 'number' ? data.totalSpendInr : parseFloat(data.totalSpendInr || 0);
        el.usageTotalSpend.textContent = `₹${spend.toFixed(2)}`;
        el.usageTotalCalls.textContent = (data.totalApiCalls || 0).toLocaleString();
        el.usageCacheHits.textContent = `${data.ttsCacheHits || 0} hits`;

        // 3. Endpoint Breakdown Table
        const breakdown = data.endpointBreakdown || {};
        const endpoints = Object.keys(breakdown);

        if (endpoints.length === 0) {
            el.usageEndpointBody.innerHTML = `<tr><td colspan="5" class="table-empty">No external API calls recorded yet for owner "${escapeHtml(data.ownerId)}".</td></tr>`;
        } else {
            el.usageEndpointBody.innerHTML = endpoints.map(epKey => {
                const row = breakdown[epKey];
                const avgLat = row.averageLatencyMs != null ? `${Math.round(row.averageLatencyMs)} ms` : '-';
                const cost = typeof row.totalCostInr === 'number' ? row.totalCostInr : parseFloat(row.totalCostInr || 0);
                return `
                    <tr>
                        <td style="font-family: monospace; font-size: 11px;">${escapeHtml(row.endpoint || epKey)}</td>
                        <td><strong>${row.callCount || 0}</strong></td>
                        <td>${(row.totalUnits || 0).toLocaleString()} ${escapeHtml(row.unitType || '')}</td>
                        <td><span style="color: var(--accent-gold); font-weight: 600;">₹${cost.toFixed(3)}</span></td>
                        <td style="color: var(--text-muted);">${avgLat}</td>
                    </tr>
                `;
            }).join('');
        }
    }

    async function loadRecentLetters() {
        try {
            const res = await fetch(`/api/documents?ownerId=${encodeURIComponent(state.ownerId)}`);
            if (!res.ok) throw new Error('Failed to load letter list');
            const letters = await res.json();

            renderRecentLetters(letters);
        } catch (err) {
            console.error('Error loading letters archive:', err);
            el.archiveLetterList.innerHTML = `<div class="drawer-empty">Failed to load letters for owner "${state.ownerId}".</div>`;
        }
    }

    function renderRecentLetters(letters) {
        if (!letters || letters.length === 0) {
            el.archiveLetterList.innerHTML = `<div class="drawer-empty">No letters found for owner "${state.ownerId}". Upload one to get started!</div>`;
            return;
        }

        el.drawerListHeader.textContent = `Recent Letters (${letters.length})`;
        el.archiveLetterList.innerHTML = letters.map(letter => {
            const yearStr = letter.year ? ` • Est. ${letter.year}` : '';
            const pageCount = letter.pages ? letter.pages.length : 0;
            const snippet = letter.pages && letter.pages[0] ? (letter.pages[0].originalText || letter.pages[0].translatedText || '') : '';
            const shortSnippet = snippet ? (snippet.length > 80 ? snippet.slice(0, 80) + '...' : snippet) : 'No snippet';

            return `
                <div class="letter-item" data-id="${letter.id}">
                    <div class="letter-item-header">
                        <span class="letter-item-title">${escapeHtml(letter.title)}</span>
                        <span class="status-pill status-${letter.status.toLowerCase()}">${letter.status}</span>
                    </div>
                    <p class="letter-item-snippet">${escapeHtml(shortSnippet)}</p>
                    <div class="letter-item-footer">
                        <span>${letter.language.toUpperCase()}${yearStr}</span>
                        <span>•</span>
                        <span>${pageCount} Page${pageCount === 1 ? '' : 's'}</span>
                    </div>
                </div>
            `;
        }).join('');

        // Attach click listeners to letter items
        el.archiveLetterList.querySelectorAll('.letter-item').forEach(item => {
            item.addEventListener('click', () => {
                const docId = item.getAttribute('data-id');
                closeArchiveDrawer();
                showToast('Loading letter...', 'info');
                loadDocumentDetails(docId);
            });
        });
    }

    async function performSearch() {
        const query = el.archiveSearchInput.value.trim();
        if (!query) {
            loadRecentLetters();
            return;
        }

        const modeRadio = document.querySelector('input[name="searchMode"]:checked');
        const mode = modeRadio ? modeRadio.value : 'indic';

        el.drawerListHeader.textContent = `Search Results for "${query}"`;
        el.archiveLetterList.innerHTML = '<div class="drawer-empty">Searching indexed letters...</div>';

        try {
            const url = `/api/documents/search?query=${encodeURIComponent(query)}&mode=${mode}&ownerId=${encodeURIComponent(state.ownerId)}`;
            const res = await fetch(url);
            if (!res.ok) throw new Error('Search failed');
            const results = await res.json();

            if (!results || results.length === 0) {
                el.archiveLetterList.innerHTML = `<div class="drawer-empty">No matches found for "${escapeHtml(query)}" in ${mode === 'indic' ? 'Indic Trigram' : 'English Full-Text'}.</div>`;
                return;
            }

            el.archiveLetterList.innerHTML = results.map(item => `
                <div class="letter-item" data-id="${item.documentId}" data-page="${item.pageNo}">
                    <div class="letter-item-header">
                        <span class="letter-item-title">${escapeHtml(item.documentTitle)}</span>
                        <span class="badge ${item.matchType === 'INDIC_TRIGRAM' ? 'badge-orig' : 'badge-trans'}">${item.matchType}</span>
                    </div>
                    <p class="letter-item-snippet">${highlightQuery(escapeHtml(item.snippet), query)}</p>
                    <div class="letter-item-footer">
                        <span>Page ${item.pageNo}</span>
                    </div>
                </div>
            `).join('');

            el.archiveLetterList.querySelectorAll('.letter-item').forEach(item => {
                item.addEventListener('click', async () => {
                    const docId = item.getAttribute('data-id');
                    const pageNo = parseInt(item.getAttribute('data-page'), 10) || 1;
                    closeArchiveDrawer();
                    await loadDocumentDetails(docId);
                    if (state.activeDoc && state.activeDoc.pages) {
                        const targetIdx = state.activeDoc.pages.findIndex(p => p.pageNo === pageNo);
                        if (targetIdx >= 0) {
                            state.currentPageIndex = targetIdx;
                            renderCurrentPage();
                        }
                    }
                });
            });

        } catch (err) {
            console.error('Search error:', err);
            el.archiveLetterList.innerHTML = '<div class="drawer-empty">Search encountered an error.</div>';
        }
    }

    function highlightQuery(text, query) {
        if (!query) return text;
        const regex = new RegExp(`(${escapeRegex(query)})`, 'gi');
        return text.replace(regex, '<mark style="background: rgba(245,158,11,0.3); color: #fbbf24; border-radius: 2px;">$1</mark>');
    }

    function escapeRegex(string) {
        return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    /* ==========================================================================
       Toast Notifications & Helpers
       ========================================================================== */
    function showToast(message, type = 'info', duration = 4000) {
        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;

        const iconSvg = type === 'success' 
            ? '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg>'
            : (type === 'error'
                ? '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="15" y1="9" x2="9" y2="15"></line><line x1="9" y1="9" x2="15" y2="15"></line></svg>'
                : '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#6366f1" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>');

        toast.innerHTML = `${iconSvg}<span>${escapeHtml(message)}</span>`;
        el.toastContainer.appendChild(toast);

        setTimeout(() => {
            toast.style.transition = 'opacity 0.3s ease, transform 0.3s ease';
            toast.style.opacity = '0';
            toast.style.transform = 'translateY(10px)';
            setTimeout(() => toast.remove(), 300);
        }, duration);
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    // Launch Application
    document.addEventListener('DOMContentLoaded', init);
})();
