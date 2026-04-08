// ═══ CONFIG ═══════════════════════════════════════════════════
const API = 'http://localhost:8080';

// ═══ STATE ════════════════════════════════════════════════════
const S = { programId: null, batchYear: null, specId: null, semesterId: null, courseFilter: null, data: null };
let barChart = null, pieChart = null, selectedZip = null, selectedPdf = null, selectedStruct = null, currentTab = 'dashboard';

// ═══ HELPERS ══════════════════════════════════════════════════
async function get(path) {
    const r = await fetch(API + path);
    if (!r.ok) throw new Error(path + ' -> HTTP ' + r.status);
    return r.json();
}
function rst(id, ph) { const e = document.getElementById(id); e.innerHTML = '<option value="">' + ph + '</option>'; e.disabled = true; }
function pct(v) { return (v == null || v === '—') ? '—' : Math.round(v) + '%'; }
function clr(v) { return v >= 60 ? 'var(--success)' : v >= 40 ? 'var(--warn)' : 'var(--danger)'; }
function bdg(v) { return v >= 60 ? 'badge-green' : v >= 40 ? 'badge-yellow' : 'badge-red'; }
function levelClr(lv) { return lv >= 3 ? 'var(--success)' : lv >= 2 ? 'var(--warn)' : lv >= 1 ? '#f59f00' : 'var(--danger)'; }
function levelBadge(lv) { return '<span style="display:inline-flex;align-items:center;justify-content:center;min-width:52px;padding:1px 7px;border-radius:20px;font-size:10px;font-weight:700;background:' + (lv >= 3 ? '#f0fdf4' : lv >= 2 ? '#fffbeb' : lv >= 1 ? '#fff7ed' : '#fef2f2') + ';color:' + levelClr(lv) + ';border:1px solid ' + (lv >= 3 ? '#bbf7d0' : lv >= 2 ? '#fde68a' : lv >= 1 ? '#fed7aa' : '#fecaca') + '">Level ' + lv + '</span>'; }

// ═══ TAB ══════════════════════════════════════════════════════
function switchTab(name, btn) {
    currentTab = name;
    document.querySelectorAll('.tab-btn').forEach(function(b) { b.classList.remove('active'); });
    if (btn) btn.classList.add('active');
    ['dashboard', 'mapping', 'students', 'upload', 'admin', 'verify'].forEach(function(v) {
        var el = document.getElementById('view-' + v);
        if (el) el.classList.add('hidden');
    });
    if (name === 'upload') {
        document.getElementById('empty-state').classList.add('hidden');
        document.getElementById('view-upload').classList.remove('hidden');
        return;
    }
    if (name === 'admin') {
        document.getElementById('empty-state').classList.add('hidden');
        document.getElementById('view-admin').classList.remove('hidden');
        return;
    }
    if (name === 'verify') {
        document.getElementById('empty-state').classList.add('hidden');
        document.getElementById('view-verify').classList.remove('hidden');
        return;
    }
    if (!S.semesterId) {
        document.getElementById('empty-state').classList.remove('hidden');
        return;
    }
    document.getElementById('empty-state').classList.add('hidden');
    document.getElementById('view-' + name).classList.remove('hidden');
    if (name === 'mapping' && S.data) renderMapping(S.data.courses || []);
}

// ═══ FILTERS ══════════════════════════════════════════════════
async function onProgChange() {
    S.programId = document.getElementById('sel_prog').value || null;
    S.batchYear = null; S.specId = null; S.semesterId = null; S.courseFilter = null;
    rst('sel_batch', '-- All Batches --'); rst('sel_spec', '-- Select Specialization --');
    rst('sel_sem', '-- Select Semester --'); rst('sel_course_filter', '-- All Courses --');
    hideData();
    if (!S.programId) return;
    try {
        const batches = await get('/dashboard/batches?programId=' + S.programId);
        const b = document.getElementById('sel_batch');
        batches.forEach(function(x) { b.add(new Option(x.label, x.year)); });
        b.disabled = batches.length === 0;
    } catch (e) { console.warn('batches', e); }
    try {
        const specs = await get('/dashboard/specializations?programId=' + S.programId);
        const s = document.getElementById('sel_spec');
        specs.forEach(function(x) { s.add(new Option(x.name, x.id)); });
        s.disabled = specs.length === 0;
        if (specs.length === 0) await loadSems();
    } catch (e) { console.warn('specs', e); }
}

async function onBatchChange() {
    S.batchYear = document.getElementById('sel_batch').value || null;
    S.semesterId = null; S.courseFilter = null;
    rst('sel_sem', '-- Select Semester --'); rst('sel_course_filter', '-- All Courses --');
    hideData(); await loadSems();
}

async function onSpecChange() {
    S.specId = document.getElementById('sel_spec').value || null;
    S.semesterId = null; S.courseFilter = null;
    rst('sel_sem', '-- Select Semester --'); rst('sel_course_filter', '-- All Courses --');
    hideData(); await loadSems();
}

async function loadSems() {
    if (!S.programId) return;
    let url = '/dashboard/semesters?programId=' + S.programId;
    if (S.specId) url += '&specializationId=' + S.specId;
    if (S.batchYear) url += '&batchYear=' + S.batchYear;
    try {
        const sems = await get(url);
        const s = document.getElementById('sel_sem');
        s.innerHTML = '<option value="">-- Select Semester --</option>';
        sems.forEach(function(x) { s.add(new Option(x.label, x.id)); });
        s.disabled = sems.length === 0;
    } catch (e) { console.warn('sems', e); }
}

async function onSemChange() {
    S.semesterId = document.getElementById('sel_sem').value || null;
    S.courseFilter = null;
    rst('sel_course_filter', '-- All Courses --');
    hideData();
    if (S.semesterId) await loadDashboard();
}

async function onCourseFilterChange() {
    S.courseFilter = document.getElementById('sel_course_filter').value || null;
    await loadDashboard();
}

// ═══ LOAD DASHBOARD ═══════════════════════════════════════════
async function loadDashboard() {
    document.getElementById('empty-state').classList.add('hidden');
    if (currentTab !== 'upload' && currentTab !== 'admin' && currentTab !== 'verify') {
        document.getElementById('view-' + currentTab).classList.remove('hidden');
    }
    try {
        // ── Step 1: get course list with IDs (includes studentCount) ─
        let coUrl = '/dashboard/courses?semesterId=' + S.semesterId + '&programId=' + S.programId;
        if (S.specId) coUrl += '&specializationId=' + S.specId;
        if (S.batchYear) coUrl += '&batchYear=' + S.batchYear;
        if (S.courseFilter) coUrl += '&courseCode=' + encodeURIComponent(S.courseFilter);
        const courseList = await get(coUrl);
        const filtered = S.courseFilter
            ? courseList.filter(function(c) { return c.code === S.courseFilter; })
            : courseList;
        const totalAllCourses = filtered.length;

        // ── Step 2: fetch CO, PO, PSO attainment + CO levels for each course
        const attainments = await Promise.all(filtered.map(function(c) {
            return Promise.all([
                get('/api/attainment/co/' + c.id).catch(function() { return {}; }),
                get('/api/attainment/po/' + c.id).catch(function() { return {}; }),
                get('/api/attainment/pso/' + c.id).catch(function() { return {}; }),
                get('/api/attainment/co-po-mapping/' + c.id).catch(function() { return []; }),
                get('/api/attainment/co-pso-mapping/' + c.id).catch(function() { return []; }),
                get('/api/attainment/co-levels/' + c.id).catch(function() { return {}; })
            ]).then(function(results) {
                return { co: results[0], po: results[1], pso: results[2], coPoMatrix: results[3], coPsoMatrix: results[4], coLevels: results[5] };
            }).catch(function() {
                return { co: {}, po: {}, pso: {}, coPoMatrix: [], coPsoMatrix: [], coLevels: {} };
            });
        }));

        // ── Step 3: build unified data structure ─────────────────
        const courses = filtered.map(function(c, idx) {
            const coMap = attainments[idx].co || {};
            const poMap = attainments[idx].po || {};
            const psoMap = attainments[idx].pso || {};
            const coLevelMap = attainments[idx].coLevels || {};
            const target = 40.0;

            const coAttainments = Object.entries(coMap).map(function(e) {
                return {
                    co: e[0],
                    description: e[0],
                    attainment: Math.round(e[1] * 10) / 10,
                    level: coLevelMap[e[0]] != null ? coLevelMap[e[0]] : null,
                    target: target
                };
            }).sort(function(a, b) {
                var na = parseInt((a.co.match(/\d+$/) || [0])[0], 10);
                var nb = parseInt((b.co.match(/\d+$/) || [0])[0], 10);
                return na - nb;
            });
            const avg = coAttainments.length
                ? Math.round(coAttainments.reduce(function(s, co) { return s + co.attainment; }, 0) / coAttainments.length * 10) / 10
                : 0;

            // PO attainment: backend returns 0-3 decimal, keep as-is
            const poHeaders = Object.keys(poMap).sort(function(a, b) {
                var na = parseInt((a.match(/\d+$/) || [0])[0], 10);
                var nb = parseInt((b.match(/\d+$/) || [0])[0], 10);
                return na - nb;
            });
            const poAttainment = {};
            poHeaders.forEach(function(po) {
                poAttainment[po] = Math.round(poMap[po] * 100) / 100;
            });

            // PSO attainment: backend returns 0-3 decimal, keep as-is
            const psoHeaders = Object.keys(psoMap).sort(function(a, b) {
                var na = parseInt((a.match(/\d+$/) || [0])[0], 10);
                var nb = parseInt((b.match(/\d+$/) || [0])[0], 10);
                return na - nb;
            });
            const psoAttainment = {};
            psoHeaders.forEach(function(pso) {
                psoAttainment[pso] = Math.round(psoMap[pso] * 100) / 100;
            });

            return {
                id: c.id, courseCode: c.code, courseName: c.name,
                studentCount: c.studentCount != null ? c.studentCount : 0,
                avgAttainment: avg,
                coAttainments: coAttainments, coLevels: coLevelMap, examLoaded: false,
                poHeaders: poHeaders, poAttainment: poAttainment,
                psoHeaders: psoHeaders, psoAttainment: psoAttainment,
                coPoMatrix: attainments[idx].coPoMatrix || [], coPsoMatrix: attainments[idx].coPsoMatrix || []
            };
        });

        const overallAtt = courses.length
            ? Math.round(courses.filter(function(c) { return c.avgAttainment > 0; })
                .reduce(function(s, c) { return s + c.avgAttainment; }, 0)
                / Math.max(1, courses.filter(function(c) { return c.avgAttainment > 0; }).length) * 10) / 10
            : 0;

        // Collect PO/PSO attainments across courses
        const branchPoAtt = {}, branchPsoAtt = {};
        courses.forEach(function(course) {
            Object.entries(course.poAttainment).forEach(function(e) {
                if (!branchPoAtt[e[0]]) branchPoAtt[e[0]] = [];
                branchPoAtt[e[0]].push(e[1]);
            });
            Object.entries(course.psoAttainment).forEach(function(e) {
                if (!branchPsoAtt[e[0]]) branchPsoAtt[e[0]] = [];
                branchPsoAtt[e[0]].push(e[1]);
            });
        });

        const branchPoAttainment = {}, branchPsoAttainment = {};
        Object.entries(branchPoAtt).forEach(function(e) {
            branchPoAttainment[e[0]] = Math.round(e[1].reduce(function(a,b) { return a+b; }, 0) / e[1].length * 10) / 10;
        });
        Object.entries(branchPsoAtt).forEach(function(e) {
            branchPsoAttainment[e[0]] = Math.round(e[1].reduce(function(a,b) { return a+b; }, 0) / e[1].length * 10) / 10;
        });

        // Total students = sum of distinct students across all courses with marks
        const totalStudents = courses.reduce(function(sum, c) { return sum + c.studentCount; }, 0);
        const coursesWithMarks = courses.filter(function(c) { return c.coAttainments.length > 0; }).length;

        const d = {
            courses: courses,
            totalCourses: coursesWithMarks,
            totalAllCourses: totalAllCourses,
            totalStudents: totalStudents,
            atRiskStudents: 0,
            overallAttainment: overallAtt,
            branchPoAttainment: branchPoAttainment,
            branchPsoAttainment: branchPsoAttainment
        };
        S.data = d;
        renderDashboard(d);
        fillCourseDropdowns(d.courses || []);
        if (currentTab === 'mapping') renderMapping(d.courses || []);
    } catch (e) { console.error('loadDashboard', e); }
}

// ═══ RENDER DASHBOARD ═════════════════════════════════════════
const COURSE_PALETTE = ['#3b5bdb','#12b886','#f59f00','#7c3aed','#e64747','#0369a1','#d97706','#0891b2'];

function renderDashboard(d) {
    const courses = d.courses || [];
    // KPI: Courses with Marks as "X / Y"
    const coursesLabel = (d.totalCourses != null ? d.totalCourses : 0) + ' / ' + (d.totalAllCourses != null ? d.totalAllCourses : courses.length);
    document.getElementById('kpi-courses').textContent = coursesLabel;
    document.getElementById('kpi-students').textContent = d.totalStudents != null && d.totalStudents > 0 ? d.totalStudents : '—';
    // Count at-risk COs (Level 1 with attainment < 40%)
    const allCOsFlat = [];
    const courseColorMap = {};
    courses.forEach(function(c, ci) {
        courseColorMap[c.courseCode] = COURSE_PALETTE[ci % COURSE_PALETTE.length];
        (c.coAttainments || []).forEach(function(co) {
            allCOsFlat.push(Object.assign({}, co, { course: c.courseCode, courseName: c.courseName, courseIdx: ci }));
        });
    });
    const atRisk = allCOsFlat.filter(function(co) { return co.attainment < 40; }).length;
    document.getElementById('kpi-risk').textContent = atRisk;

    // ── Bar chart: one color per course, dashed line for target ────────
    if (barChart) barChart.destroy();
    var barWrap = document.getElementById('attainment-table-wrap');
    if (barWrap) barWrap.innerHTML = ''; // always clear stale content

    if (allCOsFlat.length) {
        const barColors = allCOsFlat.map(function(c) { return courseColorMap[c.course] || '#3b5bdb'; });
        barChart = new Chart(document.getElementById('barChart'), {
            type: 'bar',
            data: {
                labels: allCOsFlat.map(function(c) { return c.co; }),
                datasets: [
                    {
                        label: 'Attainment %',
                        data: allCOsFlat.map(function(c) { return c.attainment; }),
                        backgroundColor: barColors,
                        borderRadius: 4, categoryPercentage: 0.7, barPercentage: 0.8, order: 2
                    },
                    {
                        type: 'line', label: 'Target (40%)',
                        data: Array(allCOsFlat.length).fill(40),
                        borderColor: '#ef4444', borderDash: [6, 4], borderWidth: 2,
                        pointRadius: 0, fill: false, tension: 0, order: 1
                    }
                ]
            },
            options: {
                responsive: true, maintainAspectRatio: false,
                interaction: { mode: 'index' },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            title: function(items) {
                                var co = allCOsFlat[items[0].dataIndex];
                                return co ? co.courseName + ' (' + co.course + ')' : '';
                            },
                            label: function(item) {
                                if (item.datasetIndex === 1) return 'Target: 40%';
                                var co = allCOsFlat[item.dataIndex];
                                var lv = co && co.level != null ? ' — Level ' + co.level : '';
                                return item.label + ': ' + item.raw + '%' + lv;
                            }
                        }
                    }
                },
                scales: {
                    y: { max: 100, min: 0, ticks: { callback: function(v) { return v + '%'; }, font: { size: 10 } }, grid: { color: 'rgba(0,0,0,0.05)' } },
                    x: { ticks: { font: { size: 10 }, maxRotation: 45 }, grid: { display: false } }
                }
            }
        });

        // Build course color legend below chart
        var legend = courses.filter(function(c) { return c.coAttainments && c.coAttainments.length > 0; }).map(function(c) {
            return '<span style="display:inline-flex;align-items:center;gap:5px;margin-right:14px;font-size:11px;font-weight:500;color:var(--text-2)">' +
                '<span style="width:10px;height:10px;border-radius:2px;background:' + courseColorMap[c.courseCode] + ';display:inline-block;flex-shrink:0"></span>' +
                '<span class="course-code" style="font-size:9px;padding:1px 5px">' + c.courseCode + '</span>' + c.courseName +
                '</span>';
        }).join('');
        if (barWrap && legend) {
            barWrap.innerHTML = '<div style="padding:10px 0 4px;display:flex;flex-wrap:wrap;gap:2px">' + legend + '</div>';
        }
    }

    // ── Course PO/PSO attainment table ────────────────────────
    var tableWrap = document.getElementById('attainment-table-wrap');
    if (tableWrap && courses.length > 0) {
        var allPoHdrs = [], allPsoHdrs = [];
        courses.forEach(function(c) {
            (c.poHeaders || []).forEach(function(p) { if (allPoHdrs.indexOf(p) < 0) allPoHdrs.push(p); });
            (c.psoHeaders || []).forEach(function(p) { if (allPsoHdrs.indexOf(p) < 0) allPsoHdrs.push(p); });
        });
        allPoHdrs.sort(function(a,b){ return parseInt((a.match(/\d+$/)||[0])[0],10)-parseInt((b.match(/\d+$/)||[0])[0],10); });
        allPsoHdrs.sort(function(a,b){ return parseInt((a.match(/\d+$/)||[0])[0],10)-parseInt((b.match(/\d+$/)||[0])[0],10); });
        var hasPo = allPoHdrs.length > 0, hasPso = allPsoHdrs.length > 0;
        var tRows = courses.filter(function(c) { return c.coAttainments && c.coAttainments.length > 0; }).map(function(c) {
            var poTds = allPoHdrs.map(function(p) {
                var v = c.poAttainment && c.poAttainment[p] != null ? c.poAttainment[p] : 0;
                return '<td style="text-align:center;color:' + clr(v/3*100) + ';font-weight:600;font-size:11px">' + v + '</td>';
            }).join('');
            var psoTds = allPsoHdrs.map(function(p) {
                var v = c.psoAttainment && c.psoAttainment[p] != null ? c.psoAttainment[p] : 0;
                return '<td style="text-align:center;color:' + clr(v/3*100) + ';font-weight:600;font-size:11px">' + v + '</td>';
            }).join('');
            return '<tr>' +
                '<td><span class="course-code" style="background:' + courseColorMap[c.courseCode] + '22;color:' + courseColorMap[c.courseCode] + ';font-size:10px">' + c.courseCode + '</span></td>' +
                '<td style="font-size:11px;max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + c.courseName + '</td>' +
                '<td style="text-align:center;font-size:11px;color:var(--text-3)">' + c.studentCount + '</td>' +
                poTds + psoTds + '</tr>';
        }).join('');
        if (tRows) {
            tableWrap.innerHTML += '<div style="margin-top:10px;border-top:1px solid var(--border);padding-top:10px">' +
                '<div style="font-size:10px;font-weight:600;text-transform:uppercase;letter-spacing:.5px;color:var(--text-3);margin-bottom:8px">Course – PO / PSO Attainment (0–3 Scale)</div>' +
                '<div style="overflow-x:auto"><table style="width:100%;border-collapse:collapse;font-size:12px">' +
                '<thead><tr>' +
                '<th style="text-align:left;padding:5px 8px;background:var(--bg);font-size:10px;font-weight:600;text-transform:uppercase;color:var(--text-3);white-space:nowrap">Code</th>' +
                '<th style="text-align:left;padding:5px 8px;background:var(--bg);font-size:10px;font-weight:600;text-transform:uppercase;color:var(--text-3)">Course Name</th>' +
                '<th style="text-align:center;padding:5px 8px;background:var(--bg);font-size:10px;font-weight:600;text-transform:uppercase;color:var(--text-3)">Students</th>' +
                (hasPo ? allPoHdrs.map(function(p) { return '<th style="text-align:center;padding:5px 8px;background:#eff6ff;font-size:10px;font-weight:600;color:#3b5bdb">' + p + '</th>'; }).join('') : '') +
                (hasPso ? allPsoHdrs.map(function(p) { return '<th style="text-align:center;padding:5px 8px;background:#f5f3ff;font-size:10px;font-weight:600;color:#7c3aed">' + p + '</th>'; }).join('') : '') +
                '</tr></thead><tbody>' + tRows + '</tbody></table></div></div>';
        }
    }

    // ── Pie chart: categorize by CO Level (1, 2, 3) ───────────────
    var coLevelCounts = [0, 0, 0]; // [Level1, Level2, Level3]
    courses.forEach(function(c) {
        Object.values(c.coLevels || {}).forEach(function(lv) {
            if (lv >= 3) coLevelCounts[2]++;
            else if (lv === 2) coLevelCounts[1]++;
            else coLevelCounts[0]++; // Level 1 (minimum, includes < 40%)
        });
    });
    if (pieChart) pieChart.destroy();
    pieChart = new Chart(document.getElementById('pieChart'), {
        type: 'doughnut',
        data: {
            labels: ['Level 1 (<60%)', 'Level 2 (60-79%)', 'Level 3 (≥80%)'],
            datasets: [{ data: coLevelCounts, backgroundColor: ['#3b5bdb', '#f59f00', '#12b886'], borderWidth: 0 }]
        },
        options: {
            responsive: true, maintainAspectRatio: false,
            plugins: { legend: { position: 'bottom', labels: { font: { size: 11 } } } },
            cutout: '65%'
        }
    });

    const list = document.getElementById('course-list');
    if (!courses.length) {
        list.innerHTML = '<div style="text-align:center;padding:48px;color:var(--text-3)"><i class="fas fa-inbox" style="font-size:32px;opacity:.3;display:block;margin-bottom:12px"></i>No courses with marks found for the selected filters.</div>';
        return;
    }
    list.innerHTML = courses.map(function(c, i) {
        const avg = c.avgAttainment != null ? c.avgAttainment : 0;
        const coRows = (c.coAttainments || []).map(function(co, j) {
            const lv = (c.coLevels && c.coLevels[co.co] != null) ? c.coLevels[co.co] : (co.level != null ? co.level : null);
            const lvBadge = lv != null ? ' &nbsp;' + levelBadge(lv) : '';
            return '<div class="co-row" style="flex-direction:column; align-items:stretch; gap:4px;">' +
                '<div style="display:flex; align-items:center; width:100%">' +
                '<span class="co-label">' + co.co + '</span>' +
                '<span class="co-desc" title="' + (co.description || '') + '">' + (co.description || '') + '</span>' +
                '<div class="co-bar-wrap"><div class="co-bar" style="width:' + Math.max(0, co.attainment) + '%;background:' + courseColorMap[c.courseCode] + '"></div></div>' +
                '<span class="co-pct" style="color:' + clr(co.attainment) + '">' + co.attainment + '%</span>' +
                lvBadge +
                '</div>' +
                '<div id="co-extra-' + i + '-' + j + '" style="margin-left:80px; font-size:12px; height:18px;"></div>' +
                '</div>';
        }).join('');
        const poHeaders = c.poHeaders || [];
        const poAtt = c.poAttainment || {};
        const poRows = poHeaders.length > 0 ? '<div style="margin-top:16px;border-top:1px solid var(--border);padding-top:12px;"><div style="font-size:11px;font-weight:600;color:var(--text-3);margin-bottom:8px;text-transform:uppercase;letter-spacing:.4px;">PO Attainment <span style="font-weight:400;font-size:10px">(0–3 scale)</span></div>' +
            '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(80px,1fr));gap:6px;">' +
            poHeaders.map(function(po) {
                const v = poAtt[po] != null ? poAtt[po] : 0;
                return '<div style="display:flex;align-items:center;gap:6px;font-size:12px;"><strong>' + po + ':</strong><span style="color:' + clr(v/3*100) + ';font-weight:600">' + v + '</span></div>';
            }).join('') + '</div></div>' : '';
        const psoHeaders = c.psoHeaders || [];
        const psoAtt = c.psoAttainment || {};
        const psoRows = psoHeaders.length > 0 ? '<div style="margin-top:12px;border-top:1px solid var(--border);padding-top:12px;"><div style="font-size:11px;font-weight:600;color:var(--text-3);margin-bottom:8px;text-transform:uppercase;letter-spacing:.4px;">PSO Attainment <span style="font-weight:400;font-size:10px">(0–3 scale)</span></div>' +
            '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(80px,1fr));gap:6px;">' +
            psoHeaders.map(function(pso) {
                const v = psoAtt[pso] != null ? psoAtt[pso] : 0;
                return '<div style="display:flex;align-items:center;gap:6px;font-size:12px;"><strong>' + pso + ':</strong><span style="color:' + clr(v/3*100) + ';font-weight:600">' + v + '</span></div>';
            }).join('') + '</div></div>' : '';
        return '<div class="course-card">' +
            '<div class="course-header" onclick="tog(' + i + ')">' +
            '<div style="display:flex;align-items:center;min-width:0;gap:10px">' +
            '<span class="course-code" style="background:' + courseColorMap[c.courseCode] + '22;color:' + courseColorMap[c.courseCode] + '">' + c.courseCode + '</span>' +
            '<span class="course-name" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">' + c.courseName + '</span>' +
            '</div>' +
            '<div class="course-meta">' +
            '<span style="font-size:12px;color:var(--text-3)">' + c.studentCount + ' students</span>' +
            '<span class="badge ' + bdg(avg) + '">Avg ' + pct(avg) + '</span>' +
            '<i class="fas fa-chevron-down" id="ch' + i + '" style="color:var(--text-3);transition:transform .2s;font-size:12px"></i>' +
            '</div></div>' +
            '<div class="course-body" id="bd' + i + '">' +
            '<div class="co-list">' + (coRows || '<p style="color:var(--text-3);font-size:13px;padding-top:10px">No COs mapped yet.</p>') + '</div>' +
            poRows + psoRows +
            '</div></div>';
    }).join('');
}

async function tog(i) {
    const b = document.getElementById('bd' + i);
    const ch = document.getElementById('ch' + i);
    const o = b.classList.toggle('open');
    ch.style.transform = o ? 'rotate(180deg)' : '';
    if (o) {
        const c = S.data && S.data.courses && S.data.courses[i];
        if (c && c.id && !c.examLoaded) {
            try {
                const examData = await get('/api/attainment/co-by-exam-type/' + c.id);
                (c.coAttainments || []).forEach(function(co, j) {
                    const midPct = examData.mid_term ? Math.round((examData.mid_term[co.co] || 0)) : 0;
                    const endPct = examData.end_term ? Math.round((examData.end_term[co.co] || 0)) : 0;
                    const el = document.getElementById('co-extra-' + i + '-' + j);
                    if (el && (midPct > 0 || endPct > 0)) {
                        el.innerHTML = '<span style="color:var(--text-3)">└ Mid Term: <b style="color:' + clr(midPct) + '">' + midPct + '%</b>' +
                            ' &nbsp; End Term: <b style="color:' + clr(endPct) + '">' + endPct + '%</b></span>';
                    }
                });
                c.examLoaded = true;
            } catch (e) { console.warn('exam-type attainment', e); }
        }
    }
}

// ═══ RENDER MAPPING ═══════════════════════════════════════════
function buildPsoRows(matrix, psos) {
    if (!matrix || !matrix.length) {
        return '<tr><td colspan="' + (psos.length + 1) + '" style="text-align:center;color:var(--text-3);padding:16px">No CO-PSO data.</td></tr>';
    }
    return matrix.map(function(r) {
        const cells = psos.map(function(ps) {
            const w = r[ps] || 0;
            return '<td><div class="score-pill s' + w + '">' + (w || '–') + '</div></td>';
        }).join('');
        return '<tr><td><strong>' + r.co + '</strong></td>' + cells + '</tr>';
    }).join('');
}

function renderMapping(courses) {
    const el = document.getElementById('mapping-content');
    if (!courses.length) { el.innerHTML = '<div style="text-align:center;padding:48px;color:var(--text-3)">No mapping data available.</div>'; return; }
    el.innerHTML = courses.map(function(c) {
        const pos = c.poHeaders || [];
        const matrix = c.coPoMatrix || [];
        const poAtt = c.poAttainment || {};
        const psos = c.psoHeaders || [];
        const psoMatrix = c.coPsoMatrix || [];
        const psoAtt = c.psoAttainment || {};

        const phdr = pos.map(function(p) { return '<th>' + p + '</th>'; }).join('');
        const rows = matrix.map(function(r) {
            return '<tr><td><strong>' + r.co + '</strong></td>' +
                pos.map(function(p) { const w = r[p] || 0; return '<td><div class="score-pill s' + w + '">' + (w || '–') + '</div></td>'; }).join('') +
                '</tr>';
        }).join('');
        const prow = pos.map(function(p) {
            const v = poAtt[p] != null ? poAtt[p] : 0;
            const pct = v / 3 * 100;
            return '<td><strong style="color:' + clr(pct) + '">' + v + '</strong></td>';
        }).join('');

        const psoHdr = psos.map(function(p) { return '<th>' + p + '</th>'; }).join('');
        const psoAttRow = psos.map(function(p) {
            const v = psoAtt[p] != null ? psoAtt[p] : 0;
            const pct = v / 3 * 100;
            return '<td><strong style="color:' + clr(pct) + '">' + v + '</strong></td>';
        }).join('');

        let html = '<div class="data-card">' +
            '<div class="data-card-header">' +
            '<div class="data-card-title"><span class="course-code">' + c.courseCode + '</span>' + c.courseName + '</div>' +
            '<span style="font-size:12px;color:var(--text-3)">' + c.studentCount + ' students · Avg ' + pct(c.avgAttainment) + '</span>' +
            '</div>';

        if (pos.length) {
            html += '<div style="padding:10px 18px 4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.5px;color:var(--text-3)">CO-PO Mapping</div>' +
                '<div class="table-wrap"><table>' +
                '<thead><tr><th>CO</th>' + phdr + '</tr></thead>' +
                '<tbody>' + (rows || '<tr><td colspan="' + (pos.length + 1) + '" style="text-align:center;color:var(--text-3);padding:16px">No CO-PO data.</td></tr>') +
                (pos.length ? '<tr style="background:var(--bg)"><td><strong>PO Attainment</strong></td>' + prow + '</tr>' : '') +
                '</tbody></table></div>';
        }

        if (psos.length) {
            html += '<div style="padding:10px 18px 4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.5px;color:var(--text-3)">CO-PSO Mapping</div>' +
                '<div class="table-wrap"><table>' +
                '<thead><tr><th>CO</th>' + psoHdr + '</tr></thead>' +
                '<tbody>' + buildPsoRows(psoMatrix, psos) +
                '<tr style="background:var(--bg)"><td><strong>PSO Attainment</strong></td>' + psoAttRow + '</tr>' +
                '</tbody></table></div>';
        }

        html += '</div>';
        return html;
    }).join('');
}

// ═══ COURSE DROPDOWNS ═════════════════════════════════════════
function fillCourseDropdowns(courses) {
    const fsel = document.getElementById('sel_course_filter');
    const prev = fsel.value;
    fsel.innerHTML = '<option value="">-- All Courses --</option>';
    courses.forEach(function(c) { fsel.add(new Option(c.courseCode + ' – ' + c.courseName, c.courseCode)); });
    if (prev) fsel.value = prev;
    fsel.disabled = courses.length === 0;
    // Student tab dropdown: use course ID as value so backend can find exact entity
    const ssel = document.getElementById('sel_course');
    ssel.innerHTML = '<option value="">-- Select a Course --</option>';
    courses.forEach(function(c) { ssel.add(new Option(c.courseCode + ' – ' + c.courseName, c.id)); });
}

// ═══ STUDENTS ═════════════════════════════════════════════════
async function loadStudents() {
    const courseId = document.getElementById('sel_course').value;
    const el = document.getElementById('students-content');
    if (!courseId) { el.innerHTML = ''; return; }
    el.innerHTML = '<div style="text-align:center;padding:30px;color:var(--text-3)">Loading...</div>';
    try {
        // Use courseId for exact course entity match (avoids findFirstByCourseCode wrong-batch bug)
        let url = '/dashboard/students?courseId=' + encodeURIComponent(courseId);
        if (S.batchYear) url += '&batchYear=' + S.batchYear;
        const d = await get(url);
        if (d.error) { el.innerHTML = '<div style="color:var(--danger);padding:16px">' + d.error + '</div>'; return; }
        const students = d.students || [], coCodes = d.coCodes || [], poHeaders = d.poHeaders || [];
        if (!students.length) { el.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-3)"><i class="fas fa-user-slash" style="font-size:28px;opacity:.3;display:block;margin-bottom:12px"></i>No student data for this course.<br><small>Make sure marks Excel files are uploaded for this course.</small></div>'; return; }
        const coHdrs = coCodes.map(function(c) { return '<th>' + c + '</th>'; }).join('');
        const poHdrs = poHeaders.map(function(p) { return '<th>' + p + ' <span style="font-weight:400;font-size:9px;opacity:.7">(0-3)</span></th>'; }).join('');
        const rows = students.map(function(s) {
            const coTds = coCodes.map(function(c) { const v = s[c] != null ? s[c] : 0; return '<td class="co-pct-cell ' + (v < 40 ? 'low' : '') + '">' + v + '%</td>'; }).join('');
            const poTds = poHeaders.map(function(p) { const v = (s.poAttainment || {})[p] != null ? (s.poAttainment || {})[p] : 0; return '<td style="color:' + clr(v/3*100) + ';font-weight:600">' + v + '</td>'; }).join('');
            const overall = s.overall != null ? s.overall : 0;
            return '<tr><td style="font-family:var(--mono);font-size:12px">' + (s.enrollmentNo || '—') + '</td><td>' + (s.name || '—') + '</td>' + coTds + poTds + '<td><strong style="color:' + clr(overall) + '">' + overall + '%</strong></td><td><span class="badge ' + (s.status === 'Pass' ? 'badge-green' : 'badge-red') + '">' + s.status + '</span></td></tr>';
        }).join('');
        el.innerHTML = '<div class="data-card"><div class="data-card-header"><div class="data-card-title"><i class="fas fa-users" style="color:var(--accent)"></i> ' + (d.courseCode || '') + ': ' + (d.courseName || '') + '</div><span style="font-size:12px;color:var(--text-3)">' + students.length + ' students</span></div><div class="table-wrap"><table><thead><tr><th>Enrollment</th><th>Name</th>' + coHdrs + poHdrs + '<th>Overall</th><th>Status</th></tr></thead><tbody>' + rows + '</tbody></table></div></div>';
    } catch (e) { el.innerHTML = '<div style="color:var(--danger);padding:16px">Error: ' + e.message + '</div>'; }
}

// ═══ UPLOAD ═══════════════════════════════════════════════════
function onZipSelect(e) { selectedZip = e.target.files[0]; document.getElementById('zipLabel').textContent = selectedZip ? selectedZip.name : 'Click to select ZIP'; document.getElementById('zipName').textContent = selectedZip ? selectedZip.name : ''; document.getElementById('zipBtn').disabled = !selectedZip; }
function onPdfSelect(e) { selectedPdf = e.target.files[0]; document.getElementById('pdfLabel').textContent = selectedPdf ? selectedPdf.name : 'Click to select PDF'; document.getElementById('pdfName').textContent = selectedPdf ? selectedPdf.name : ''; document.getElementById('pdfBtn').disabled = !selectedPdf; }
function onStructSelect(e) { selectedStruct = e.target.files[0]; document.getElementById('structLabel').textContent = selectedStruct ? selectedStruct.name : 'Click to select .xlsx'; document.getElementById('structName').textContent = selectedStruct ? selectedStruct.name : ''; document.getElementById('structBtn').disabled = !selectedStruct; }

function handleDrop(e, id) {
    e.preventDefault(); e.currentTarget.classList.remove('over');
    const f = e.dataTransfer.files[0]; if (!f) return;
    const inp = document.getElementById(id), dt = new DataTransfer();
    dt.items.add(f); inp.files = dt.files; inp.dispatchEvent(new Event('change'));
}

async function uploadZip() {
    if (!selectedZip) return;
    const btn = document.getElementById('zipBtn'), res = document.getElementById('zipResult');
    btn.innerHTML = '<span class="spin"></span> Uploading...'; btn.disabled = true; res.className = 'upload-result';
    try {
        const fd = new FormData(); fd.append('file', selectedZip);
        const r = await fetch(API + '/marks/upload-zip', { method: 'POST', body: fd });
        const d = await r.json();
        const errs = d.errors && d.errors.length ? '<br><small style="color:#dc2626">Skipped: ' + d.errors.length + ' files</small>' : '';
        res.className = 'upload-result success';
        res.innerHTML = '<strong>' + d.files_processed + ' files processed</strong> · ' + (d.total_mark_records_saved || 0).toLocaleString() + ' marks saved' + errs;
    } catch (e) { res.className = 'upload-result error'; res.innerHTML = e.message; }
    btn.innerHTML = '<i class="fas fa-upload"></i> Upload'; btn.disabled = false;
}

async function uploadPdf() {
    if (!selectedPdf) return;
    const btn = document.getElementById('pdfBtn'), res = document.getElementById('pdfResult');
    btn.innerHTML = '<span class="spin"></span> Parsing...'; btn.disabled = true; res.className = 'upload-result';
    try {
        const fd = new FormData(); fd.append('file', selectedPdf);
        const prog = document.getElementById('pdfProgOverride').value, year = document.getElementById('pdfBatchYear').value;
        if (prog) fd.append('programName', prog); if (year) fd.append('batchYear', year);
        const r = await fetch(API + '/handbook/upload', { method: 'POST', body: fd });
        const d = await r.json();
        res.className = 'upload-result success';
        res.innerHTML = '<strong>Parsed!</strong><br>' + d.program + ' · ' + (d.specialization || '—') + ' · Batch ' + d.batch + '<br>' + d.semesters_found + ' semesters · ' + d.courses_found + ' courses · ' + d.cos_found + ' COs · ' + d.pos_found + ' POs · ' + d.psos_found + ' PSOs<br>' + d.copo_mappings + ' CO-PO · ' + d.copso_mappings + ' CO-PSO mappings';
    } catch (e) { res.className = 'upload-result error'; res.innerHTML = e.message; }
    btn.innerHTML = '<i class="fas fa-cogs"></i> Parse Handbook'; btn.disabled = false;
}

async function uploadStruct() {
    if (!selectedStruct) return;
    const btn = document.getElementById('structBtn'), res = document.getElementById('structResult');
    btn.innerHTML = '<span class="spin"></span> Creating...'; btn.disabled = true; res.className = 'upload-result';
    try {
        const fd = new FormData(); fd.append('file', selectedStruct);
        const r = await fetch(API + '/structure/upload', { method: 'POST', body: fd });
        const d = await r.json();
        if (d.status === 'success') {
            res.className = 'upload-result success';
            res.innerHTML = '<strong>' + d.programme + '</strong> · ' + (d.specialization || '—') + ' · Batch ' + d.batch + '<br>' +
                d.semesters + ' semesters · ' + d.courses + ' courses · ' + d.cos + ' COs · ' + d.pos + ' POs · ' + d.psos + ' PSOs<br>' +
                d.copo_mappings + ' CO-PO · ' + d.copso_mappings + ' CO-PSO mappings';
        } else {
            res.className = 'upload-result error';
            res.innerHTML = d.message || JSON.stringify(d);
        }
    } catch (e) { res.className = 'upload-result error'; res.innerHTML = e.message; }
    btn.innerHTML = '<i class="fas fa-database"></i> Create All Entities'; btn.disabled = false;
}

// ═══ VERIFY ════════════════════════════════════════════════════
async function verifyExcel(event) {
    const file = event.target.files[0]; if (!file) return;
    const el = document.getElementById('verify-content');
    el.innerHTML = '<div style="text-align:center;padding:30px;color:var(--text-3)">Comparing...</div>';
    try {
        const fd = new FormData(); fd.append('file', file);
        const r = await fetch(API + '/structure/verify', { method: 'POST', body: fd });
        renderVerifyResult(await r.json());
    } catch (e) { el.innerHTML = '<div style="color:var(--danger);padding:16px">Error: ' + e.message + '</div>'; }
    event.target.value = '';
}

async function loadVerify() {
    const el = document.getElementById('verify-content');
    el.innerHTML = '<div style="text-align:center;padding:30px;color:var(--text-3)">Loading...</div>';
    try {
        const d = await get('/dashboard/verify');
        const stats = d.stats || {};
        const keys = ['programmes','specializations','batches','semesters','courses','cos','pos','psos','copo_mappings','copso_mappings','students','marks'];
        let html = '<div class="kpi-grid" style="grid-template-columns:repeat(6,1fr);margin-bottom:20px">';
        keys.forEach(function(k) {
            html += '<div class="kpi" style="flex-direction:column;align-items:flex-start;gap:4px"><div class="kpi-val" style="font-size:18px">' + (stats[k] || 0).toLocaleString() + '</div><div class="kpi-label">' + k.replace(/_/g, ' ') + '</div></div>';
        });
        html += '</div>';
        const courses = d.courses || [];
        html += '<div class="data-card" style="margin-bottom:14px"><div class="data-card-header"><div class="data-card-title">Courses</div><span style="font-size:12px;color:var(--text-3)">' + courses.length + ' total</span></div><div class="table-wrap"><table><thead><tr><th>Code</th><th>Name</th><th>Sem</th><th>COs</th><th>CO-PO</th><th>CO-PSO</th><th>Marks</th><th>Students</th></tr></thead><tbody>';
        courses.forEach(function(c) {
            html += '<tr><td><span class="course-code">' + c.courseCode + '</span></td><td style="font-size:12px">' + c.courseName + '</td><td>' + (c.semester || '—') + '</td>' +
                '<td><span class="badge ' + (c.cos > 0 ? 'badge-green' : 'badge-red') + '">' + c.cos + '</span></td>' +
                '<td><span class="badge ' + (c.copoMappings > 0 ? 'badge-green' : 'badge-yellow') + '">' + c.copoMappings + '</span></td>' +
                '<td><span class="badge ' + (c.copsoMappings > 0 ? 'badge-green' : 'badge-yellow') + '">' + c.copsoMappings + '</span></td>' +
                '<td>' + (c.marks || 0).toLocaleString() + '</td><td>' + (c.students || 0) + '</td></tr>';
        });
        html += '</tbody></table></div></div>';
        const pos = d.pos || [], psos = d.psos || [];
        html += '<div style="display:grid;grid-template-columns:1fr 1fr;gap:14px">';
        html += '<div class="data-card"><div class="data-card-header"><div class="data-card-title">Programme Outcomes (PO)</div></div><div class="table-wrap"><table><thead><tr><th>Code</th><th>Description</th></tr></thead><tbody>';
        if (pos.length) pos.forEach(function(p) { html += '<tr><td><strong>' + p.code + '</strong></td><td style="font-size:12px">' + (p.description || '—') + '</td></tr>'; });
        else html += '<tr><td colspan="2" style="color:var(--text-3);text-align:center;padding:16px">No POs found</td></tr>';
        html += '</tbody></table></div></div>';
        html += '<div class="data-card"><div class="data-card-header"><div class="data-card-title">Programme Specific Outcomes (PSO)</div></div><div class="table-wrap"><table><thead><tr><th>Code</th><th>Description</th></tr></thead><tbody>';
        if (psos.length) psos.forEach(function(p) { html += '<tr><td><strong>' + p.code + '</strong></td><td style="font-size:12px">' + (p.description || '—') + '</td></tr>'; });
        else html += '<tr><td colspan="2" style="color:var(--text-3);text-align:center;padding:16px">No PSOs found</td></tr>';
        html += '</tbody></table></div></div></div>';
        el.innerHTML = html;
    } catch (e) { el.innerHTML = '<div style="color:var(--danger);padding:16px">Error: ' + e.message + '</div>'; }
}

function renderVerifyResult(d) {
    const el = document.getElementById('verify-content');
    const checks = d.checks || [];
    const passed = checks.filter(function(c) { return c.status === 'OK'; }).length;
    const failed = checks.filter(function(c) { return c.status === 'MISSING' || c.status === 'MISMATCH'; }).length;
    const warn = checks.filter(function(c) { return c.status === 'WARN'; }).length;
    const statusColor = { OK: 'var(--success)', MISSING: 'var(--danger)', MISMATCH: 'var(--warn)', WARN: 'var(--warn)' };
    let html = '<div style="display:flex;gap:12px;margin-bottom:16px">' +
        '<div class="kpi" style="flex:1"><div class="kpi-icon green"><i class="fas fa-check"></i></div><div><div class="kpi-val">' + passed + '</div><div class="kpi-label">Passed</div></div></div>' +
        '<div class="kpi" style="flex:1"><div class="kpi-icon red"><i class="fas fa-times"></i></div><div><div class="kpi-val">' + failed + '</div><div class="kpi-label">Failed</div></div></div>' +
        '<div class="kpi" style="flex:1"><div class="kpi-icon orange"><i class="fas fa-exclamation"></i></div><div><div class="kpi-val">' + warn + '</div><div class="kpi-label">Warnings</div></div></div></div>';
    html += '<div class="data-card"><div class="data-card-header"><div class="data-card-title">Verification Results</div></div><div class="table-wrap"><table><thead><tr><th style="text-align:left">Check</th><th>Expected</th><th>In DB</th><th>Status</th></tr></thead><tbody>';
    checks.forEach(function(c) {
        html += '<tr><td><strong>' + c.label + '</strong></td><td style="font-family:var(--mono);font-size:12px">' + (c.expected || '—') + '</td><td style="font-family:var(--mono);font-size:12px">' + (c.actual || '—') + '</td><td style="color:' + (statusColor[c.status] || 'inherit') + ';font-weight:600">' + c.status + '</td></tr>';
    });
    html += '</tbody></table></div></div>';
    el.innerHTML = html;
}

// ═══ HIDE DATA ════════════════════════════════════════════════
function hideData() {
    ['dashboard', 'mapping', 'students', 'verify'].forEach(function(v) {
        document.getElementById('view-' + v).classList.add('hidden');
    });
    document.getElementById('empty-state').classList.remove('hidden');
    document.getElementById('course-list').innerHTML = '';
    document.getElementById('mapping-content').innerHTML = '';
    document.getElementById('students-content').innerHTML = '';
    document.getElementById('sel_course').innerHTML = '<option value="">-- Select a Course --</option>';
}

// ═══ INIT ═════════════════════════════════════════════════════
(async function() {
    try {
        const progs = await get('/dashboard/programs');
        const sel = document.getElementById('sel_prog');
        progs.forEach(function(p) { sel.add(new Option(p.name, p.id)); });
        sel.disabled = progs.length === 0;
    } catch (e) { console.warn('programs', e); }
})();

// ═══════════════════════════════════════════════════════════════
// ADMIN PANEL
// ═══════════════════════════════════════════════════════════════

const ADM = {};

async function adminApi(method, path, body) {
    const opts = { method: method, headers: { 'Content-Type': 'application/json' } };
    if (body) opts.body = JSON.stringify(body);
    const r = await fetch(API + path, opts);
    if (!r.ok) throw new Error(await r.text());
    return r.json();
}

function adminSection(sec, btn) {
    document.querySelectorAll('.admin-nav-btn').forEach(function(b) { b.classList.remove('active'); });
    if (btn) btn.classList.add('active');
    ADM.section = sec;
    const map = { programs: loadAdminPrograms, batches: loadAdminBatches, semesters: loadAdminSemesters, courses: loadAdminCourses, cos: loadAdminCOs, pos: loadAdminPOs, psos: loadAdminPSOs, copo: loadAdminCoPo, copso: loadAdminCoPso };
    if (map[sec]) map[sec]();
}

function ac(tag, props) {
    const el = document.createElement(tag);
    const children = Array.prototype.slice.call(arguments, 2);
    Object.entries(props || {}).forEach(function(entry) {
        const k = entry[0], v = entry[1];
        if (k === 'style') Object.assign(el.style, v);
        else if (k === 'class') el.className = v;
        else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v);
    });
    children.forEach(function(c) { el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return el;
}

function adminWrap(title) {
    const nodes = Array.prototype.slice.call(arguments, 1);
    const el = document.getElementById('admin-content');
    el.innerHTML = '';
    const card = ac('div', { class: 'data-card', style: { padding: '20px' } });
    card.appendChild(ac('h3', { style: { marginBottom: '16px', fontSize: '16px', color: 'var(--accent)' } }, title));
    nodes.forEach(function(n) { card.appendChild(n); });
    el.appendChild(card);
}

function adminMsg(txt, ok) {
    if (ok === undefined) ok = true;
    const m = ac('div', { style: { padding: '8px 12px', borderRadius: '7px', fontSize: '12px', fontWeight: 600, marginTop: '10px', background: ok ? '#f0fdf4' : '#fef2f2', color: ok ? '#166534' : '#991b1b' } }, txt);
    document.getElementById('admin-content').querySelectorAll('.admin-msg').forEach(function(x) { x.remove(); });
    m.className = 'admin-msg';
    const card = document.getElementById('admin-content').querySelector('.data-card');
    if (card) card.appendChild(m);
    setTimeout(function() { m.remove(); }, 3000);
}

function entityTable(cols, rows, actions) {
    const tbl = ac('table', { style: { width: '100%', borderCollapse: 'collapse', fontSize: '13px' } });
    const thead = ac('thead'), hr = ac('tr');
    cols.concat(['Actions']).forEach(function(c) { hr.appendChild(ac('th', { style: { textAlign: 'left', padding: '8px 10px', borderBottom: '2px solid var(--border)', color: 'var(--text-3)', fontSize: '11px', textTransform: 'uppercase' } }, c)); });
    thead.appendChild(hr); tbl.appendChild(thead);
    const tbody = ac('tbody');
    rows.forEach(function(row) {
        const tr = ac('tr');
        cols.forEach(function(c) { tr.appendChild(ac('td', { style: { padding: '8px 10px', borderBottom: '1px solid var(--border)' } }, String(row[c] != null ? row[c] : '—'))); });
        const actTd = ac('td', { style: { padding: '8px 10px', borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap' } });
        actions(row).forEach(function(b) { actTd.appendChild(b); });
        tr.appendChild(actTd); tbody.appendChild(tr);
    });
    tbl.appendChild(tbody); return tbl;
}

function btn(label, cls, onclick) { return ac('button', { class: 'btn-sm ' + cls, style: { marginRight: '4px' }, onclick: onclick }, label); }

async function loadAdminPrograms() {
    const progs = await adminApi('GET', '/admin/programs');
    const form = ac('div', { class: 'admin-form' });
    const inp = ac('input', { type: 'text', placeholder: 'Program name' });
    form.appendChild(inp);
    form.appendChild(btn('Add', 'btn-save', async function() {
        if (!inp.value.trim()) return;
        await adminApi('POST', '/admin/programs', { name: inp.value.trim() });
        inp.value = ''; loadAdminPrograms(); adminMsg('Added!');
    }));
    const tbl = entityTable(['id', 'name'], progs, function(row) {
        return [
            btn('Edit', 'btn-edit', function() {
                const name = prompt('New name:', row.name);
                if (name) adminApi('PUT', '/admin/programs/' + row.id, { name: name }).then(function() { loadAdminPrograms(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function() {
                if (!confirm('Delete "' + row.name + '"?')) return;
                await adminApi('DELETE', '/admin/programs/' + row.id); loadAdminPrograms(); adminMsg('Deleted.');
            })
        ];
    });
    adminWrap('Programs', form, tbl);
}

async function loadAdminBatches() {
    const results = await Promise.all([adminApi('GET', '/admin/batches'), adminApi('GET', '/admin/programs')]);
    const batches = results[0], progs = results[1];
    const form = ac('div', { class: 'admin-form' });
    const pSel = ac('select', {}); pSel.appendChild(ac('option', { value: '' }, '-- Program --'));
    progs.forEach(function(p) { pSel.appendChild(ac('option', { value: p.id }, p.name)); });
    const sYear = ac('input', { type: 'number', placeholder: 'Start Year', style: { width: '100px' } });
    const eYear = ac('input', { type: 'number', placeholder: 'End Year', style: { width: '100px' } });
    form.appendChild(pSel); form.appendChild(sYear); form.appendChild(eYear);
    form.appendChild(btn('Add', 'btn-save', async function() {
        if (!pSel.value || !sYear.value) return;
        await adminApi('POST', '/admin/batches', { programId: pSel.value, startYear: sYear.value, endYear: eYear.value });
        sYear.value = ''; eYear.value = ''; loadAdminBatches(); adminMsg('Added!');
    }));
    const tbl = entityTable(['id', 'label', 'programName'], batches, function(row) {
        return [
            btn('Edit', 'btn-edit', function() {
                const sy = prompt('Start year:', row.startYear), ey = prompt('End year:', row.endYear);
                if (sy) adminApi('PUT', '/admin/batches/' + row.id, { startYear: sy, endYear: ey }).then(function() { loadAdminBatches(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function() {
                if (!confirm('Delete?')) return;
                await adminApi('DELETE', '/admin/batches/' + row.id); loadAdminBatches(); adminMsg('Deleted.');
            })
        ];
    });
    adminWrap('Batches', form, tbl);
}

async function loadAdminSemesters() {
    const results = await Promise.all([adminApi('GET', '/admin/semesters'), adminApi('GET', '/admin/batches')]);
    const sems = results[0], batches = results[1];
    const form = ac('div', { class: 'admin-form' });
    const bSel = ac('select', {}); bSel.appendChild(ac('option', { value: '' }, '-- Batch --'));
    batches.forEach(function(b) { bSel.appendChild(ac('option', { value: b.id }, b.programName + ' ' + b.label)); });
    const num = ac('input', { type: 'number', placeholder: 'Semester No.', min: 1, max: 12, style: { width: '100px' } });
    form.appendChild(bSel); form.appendChild(num);
    form.appendChild(btn('Add', 'btn-save', async function() {
        if (!bSel.value || !num.value) return;
        await adminApi('POST', '/admin/semesters', { batchId: bSel.value, number: num.value });
        num.value = ''; loadAdminSemesters(); adminMsg('Added!');
    }));
    const disp = sems.map(function(s) { return Object.assign({}, s, { batchLabel: s.batchLabel || '—', label: 'Semester ' + s.number }); });
    const tbl = entityTable(['id', 'label', 'batchLabel'], disp, function(row) {
        return [
            btn('Edit', 'btn-edit', function() {
                const n = prompt('Semester number:', row.number);
                if (n) adminApi('PUT', '/admin/semesters/' + row.id, { number: n }).then(function() { loadAdminSemesters(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function() {
                if (!confirm('Delete?')) return;
                await adminApi('DELETE', '/admin/semesters/' + row.id); loadAdminSemesters(); adminMsg('Deleted.');
            })
        ];
    });
    adminWrap('Semesters', form, tbl);
}

async function loadAdminCourses() {
    const results = await Promise.all([adminApi('GET', '/admin/courses'), adminApi('GET', '/admin/semesters'), adminApi('GET', '/admin/programs'), adminApi('GET', '/admin/batches')]);
    const courses = results[0], sems = results[1], progs = results[2], batches = results[3];
    const form = ac('div', { class: 'admin-form' });
    const codeI = ac('input', { type: 'text', placeholder: 'Code' });
    const nameI = ac('input', { type: 'text', placeholder: 'Course Name', style: { minWidth: '200px' } });
    const pSel = ac('select', {}); pSel.appendChild(ac('option', { value: '' }, '-- Program --'));
    progs.forEach(function(p) { pSel.appendChild(ac('option', { value: p.id }, p.name)); });
    const bSel = ac('select', {}); bSel.appendChild(ac('option', { value: '' }, '-- Batch --'));
    batches.forEach(function(b) { bSel.appendChild(ac('option', { value: b.id }, b.programName + ' ' + b.label)); });
    const sSel = ac('select', {}); sSel.appendChild(ac('option', { value: '' }, '-- Semester --'));
    sems.forEach(function(s) { sSel.appendChild(ac('option', { value: s.id }, 'Sem ' + s.number + ' (' + s.batchLabel + ')')); });
    form.appendChild(codeI); form.appendChild(nameI); form.appendChild(pSel); form.appendChild(bSel); form.appendChild(sSel);
    form.appendChild(btn('Add', 'btn-save', async function() {
        if (!codeI.value || !nameI.value) return;
        await adminApi('POST', '/admin/courses', { courseCode: codeI.value, courseName: nameI.value, programId: pSel.value, batchId: bSel.value, semesterId: sSel.value });
        codeI.value = ''; nameI.value = ''; loadAdminCourses(); adminMsg('Added!');
    }));
    const tbl = entityTable(['id', 'courseCode', 'courseName', 'programName', 'semesterNumber'], courses, function(row) {
        return [
            btn('Edit', 'btn-edit', function() {
                const code = prompt('Code:', row.courseCode), name = prompt('Name:', row.courseName);
                if (code && name) adminApi('PUT', '/admin/courses/' + row.id, { courseCode: code, courseName: name }).then(function() { loadAdminCourses(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function() {
                if (!confirm('Delete "' + row.courseCode + '"?')) return;
                await adminApi('DELETE', '/admin/courses/' + row.id); loadAdminCourses(); adminMsg('Deleted.');
            })
        ];
    });
    adminWrap('Courses', form, tbl);
}

async function loadAdminCOs() {
    const courses = await adminApi('GET', '/admin/courses');
    const form = ac('div', { class: 'admin-form' });
    const cSel = ac('select', {}); cSel.appendChild(ac('option', { value: '' }, '-- Course --'));
    courses.forEach(function(c) { cSel.appendChild(ac('option', { value: c.id }, c.courseCode + ' – ' + c.courseName)); });
    form.appendChild(cSel);
    form.appendChild(btn('Load COs', 'btn-save', function() { loadAdminCOsForCourse(cSel.value); }));
    const addForm = ac('div', { class: 'admin-form', style: { marginTop: '12px' } });
    const codeI = ac('input', { type: 'text', placeholder: 'CO Code (e.g. CO1)' });
    const descI = ac('input', { type: 'text', placeholder: 'Description', style: { minWidth: '250px' } });
    addForm.appendChild(codeI); addForm.appendChild(descI);
    addForm.appendChild(btn('Add CO', 'btn-save', async function() {
        if (!cSel.value || !codeI.value) return;
        await adminApi('POST', '/admin/cos', { courseId: cSel.value, code: codeI.value, description: descI.value });
        codeI.value = ''; descI.value = ''; loadAdminCOsForCourse(cSel.value); adminMsg('Added!');
    }));
    const tblWrap = ac('div', {});
    tblWrap.appendChild(ac('p', { style: { color: 'var(--text-3)', fontSize: '13px' } }, 'Select a course above to view its COs.'));
    adminWrap('Course Outcomes (COs)', form, addForm, tblWrap);
}

async function loadAdminCOsForCourse(courseId) {
    if (!courseId) return;
    const cos = await adminApi('GET', '/admin/cos?courseId=' + courseId);
    const card = document.getElementById('admin-content').querySelector('.data-card');
    let tblWrap = card.querySelector('.co-table-wrap');
    if (!tblWrap) { tblWrap = ac('div', { class: 'co-table-wrap' }); card.appendChild(tblWrap); }
    const tbl = entityTable(['id', 'code', 'description', 'courseCode'], cos, function(row) {
        return [
            btn('Edit', 'btn-edit', function() {
                const code = prompt('CO Code:', row.code), desc = prompt('Description:', row.description);
                if (code) adminApi('PUT', '/admin/cos/' + row.id, { code: code, description: desc }).then(function() { loadAdminCOsForCourse(courseId); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function() {
                if (!confirm('Delete "' + row.code + '"?')) return;
                await adminApi('DELETE', '/admin/cos/' + row.id); loadAdminCOsForCourse(courseId); adminMsg('Deleted.');
            })
        ];
    });
    tblWrap.innerHTML = ''; tblWrap.appendChild(tbl);
}

async function loadAdminPOs() {
    const results = await Promise.all([adminApi('GET', '/admin/pos'), adminApi('GET', '/admin/programs')]);
    const pos = results[0], progs = results[1];
    const form = ac('div', { class: 'admin-form' });
    const pSel = ac('select', {}); pSel.appendChild(ac('option', { value: '' }, '-- Program --'));
    progs.forEach(function(p) { pSel.appendChild(ac('option', { value: p.id }, p.name)); });
    const codeI = ac('input', { type: 'text', placeholder: 'PO Code (e.g. PO1)' });
    const descI = ac('input', { type: 'text', placeholder: 'Description', style: { minWidth: '250px' } });
    form.appendChild(pSel); form.appendChild(codeI); form.appendChild(descI);
    form.appendChild(btn('Add PO', 'btn-save', async function() {
        if (!pSel.value || !codeI.value) return;
        await adminApi('POST', '/admin/pos', { programId: pSel.value, code: codeI.value, description: descI.value });
        codeI.value = ''; descI.value = ''; loadAdminPOs(); adminMsg('Added!');
    }));
    const tbl = entityTable(['id', 'code', 'description', 'programName'], pos, function(row) {
        return [
            btn('Edit', 'btn-edit', function() {
                const code = prompt('PO Code:', row.code), desc = prompt('Description:', row.description);
                if (code) adminApi('PUT', '/admin/pos/' + row.id, { code: code, description: desc }).then(function() { loadAdminPOs(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function() {
                if (!confirm('Delete "' + row.code + '"?')) return;
                await adminApi('DELETE', '/admin/pos/' + row.id); loadAdminPOs(); adminMsg('Deleted.');
            })
        ];
    });
    adminWrap('Programme Outcomes (POs)', form, tbl);
}

async function loadAdminPSOs() {
    const results = await Promise.all([adminApi('GET', '/admin/psos'), adminApi('GET', '/admin/programs')]);
    const psos = results[0], progs = results[1];
    const form = ac('div', { class: 'admin-form' });
    const pSel = ac('select', {}); pSel.appendChild(ac('option', { value: '' }, '-- Program --'));
    progs.forEach(function(p) { pSel.appendChild(ac('option', { value: p.id }, p.name)); });
    const codeI = ac('input', { type: 'text', placeholder: 'PSO Code (e.g. PSO1)' });
    const descI = ac('input', { type: 'text', placeholder: 'Description', style: { minWidth: '250px' } });
    form.appendChild(pSel); form.appendChild(codeI); form.appendChild(descI);
    form.appendChild(btn('Add PSO', 'btn-save', async function() {
        if (!pSel.value || !codeI.value) return;
        await adminApi('POST', '/admin/psos', { programId: pSel.value, code: codeI.value, description: descI.value });
        codeI.value = ''; descI.value = ''; loadAdminPSOs(); adminMsg('Added!');
    }));
    const tbl = entityTable(['id', 'code', 'description', 'programName'], psos, function(row) {
        return [
            btn('Edit', 'btn-edit', function() {
                const code = prompt('PSO Code:', row.code), desc = prompt('Description:', row.description);
                if (code) adminApi('PUT', '/admin/psos/' + row.id, { code: code, description: desc }).then(function() { loadAdminPSOs(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function() {
                if (!confirm('Delete "' + row.code + '"?')) return;
                await adminApi('DELETE', '/admin/psos/' + row.id); loadAdminPSOs(); adminMsg('Deleted.');
            })
        ];
    });
    adminWrap('Programme Specific Outcomes (PSOs)', form, tbl);
}

const matrixWeights = {};

async function loadAdminCoPo() {
    const courses = await adminApi('GET', '/admin/courses');
    const form = ac('div', { class: 'admin-form' });
    const cSel = ac('select', {}); cSel.appendChild(ac('option', { value: '' }, '-- Select Course --'));
    courses.forEach(function(c) { cSel.appendChild(ac('option', { value: c.id }, c.courseCode + ' – ' + c.courseName)); });
    form.appendChild(cSel);
    form.appendChild(btn('Load Matrix', 'btn-save', function() { renderCoPoMatrix(cSel.value); }));
    adminWrap('CO-PO Mapping Matrix', form, ac('div', {}));
}

async function renderCoPoMatrix(courseId) {
    if (!courseId) return;
    const d = await adminApi('GET', '/admin/copo?courseId=' + courseId);
    const cos = d.cos, pos = d.pos, weights = d.weights;
    Object.assign(matrixWeights, weights || {});
    const card = document.getElementById('admin-content').querySelector('.data-card');
    const old = card.querySelector('.matrix-container'); if (old) old.remove();
    const wrap = ac('div', { class: 'matrix-container', style: { overflowX: 'auto', marginTop: '12px' } });
    wrap.appendChild(ac('div', { style: { fontSize: '11px', color: 'var(--text-3)', marginBottom: '8px' } }, 'Click a cell to cycle: 0 -> 1 -> 2 -> 3 -> 0'));
    const tbl = ac('table', { style: { borderCollapse: 'separate', borderSpacing: '3px' } });
    const hrow = ac('tr');
    hrow.appendChild(ac('th', { style: { minWidth: '120px', textAlign: 'left', padding: '4px 8px', fontSize: '11px' } }, 'CO \\ PO'));
    pos.forEach(function(po) { const th = ac('th', { style: { minWidth: '38px', textAlign: 'center', fontSize: '11px', padding: '2px' } }, po.code); th.title = po.description || po.code; hrow.appendChild(th); });
    tbl.appendChild(hrow);
    cos.forEach(function(co) {
        const row = ac('tr');
        const labTd = ac('td', { style: { padding: '4px 8px', fontSize: '12px', fontWeight: 600 } }, co.code);
        labTd.title = co.description || co.code; row.appendChild(labTd);
        pos.forEach(function(po) {
            const w = (matrixWeights[co.id] || {})[po.id] || 0;
            const cell = ac('td', {});
            const inp = ac('button', { class: 'matrix-cell w' + w, 'data-co': co.id, 'data-po': po.id }, String(w));
            inp.addEventListener('click', function() {
                const nw = (parseInt(inp.textContent) + 1) % 4;
                inp.textContent = nw; inp.className = 'matrix-cell w' + nw;
                if (!matrixWeights[co.id]) matrixWeights[co.id] = {};
                matrixWeights[co.id][po.id] = nw;
            });
            cell.appendChild(inp); row.appendChild(cell);
        });
        tbl.appendChild(row);
    });
    wrap.appendChild(tbl);
    const saveBtn = ac('button', { class: 'btn-sm btn-save', style: { marginTop: '14px' } }, 'Save CO-PO Mapping');
    saveBtn.addEventListener('click', async function() {
        await adminApi('PUT', '/admin/copo', { courseId: courseId, weights: matrixWeights });
        adminMsg('CO-PO mapping saved!');
    });
    wrap.appendChild(saveBtn);
    card.appendChild(wrap);
}

const psoMatrixWeights = {};

async function loadAdminCoPso() {
    const courses = await adminApi('GET', '/admin/courses');
    const form = ac('div', { class: 'admin-form' });
    const cSel = ac('select', {}); cSel.appendChild(ac('option', { value: '' }, '-- Select Course --'));
    courses.forEach(function(c) { cSel.appendChild(ac('option', { value: c.id }, c.courseCode + ' – ' + c.courseName)); });
    form.appendChild(cSel);
    form.appendChild(btn('Load Matrix', 'btn-save', function() { renderCoPsoMatrix(cSel.value); }));
    adminWrap('CO-PSO Mapping Matrix', form, ac('div', {}));
}

async function renderCoPsoMatrix(courseId) {
    if (!courseId) return;
    const d = await adminApi('GET', '/admin/copso?courseId=' + courseId);
    const cos = d.cos, psos = d.psos, weights = d.weights;
    Object.assign(psoMatrixWeights, weights || {});
    const card = document.getElementById('admin-content').querySelector('.data-card');
    const old = card.querySelector('.pso-matrix-container'); if (old) old.remove();
    const wrap = ac('div', { class: 'pso-matrix-container', style: { overflowX: 'auto', marginTop: '12px' } });
    if (!psos.length) { wrap.appendChild(ac('p', { style: { color: 'var(--text-3)' } }, 'No PSOs found. Add PSOs first.')); card.appendChild(wrap); return; }
    wrap.appendChild(ac('div', { style: { fontSize: '11px', color: 'var(--text-3)', marginBottom: '8px' } }, 'Click a cell to cycle: 0 -> 1 -> 2 -> 3 -> 0'));
    const tbl = ac('table', { style: { borderCollapse: 'separate', borderSpacing: '3px' } });
    const hrow = ac('tr');
    hrow.appendChild(ac('th', { style: { minWidth: '120px', textAlign: 'left', padding: '4px 8px', fontSize: '11px' } }, 'CO \\ PSO'));
    psos.forEach(function(pso) { const th = ac('th', { style: { minWidth: '38px', textAlign: 'center', fontSize: '11px', padding: '2px' } }, pso.code); th.title = pso.description || pso.code; hrow.appendChild(th); });
    tbl.appendChild(hrow);
    cos.forEach(function(co) {
        const row = ac('tr');
        const labTd = ac('td', { style: { padding: '4px 8px', fontSize: '12px', fontWeight: 600 } }, co.code);
        labTd.title = co.description || co.code; row.appendChild(labTd);
        psos.forEach(function(pso) {
            const w = (psoMatrixWeights[co.id] || {})[pso.id] || 0;
            const cell = ac('td', {});
            const inp = ac('button', { class: 'matrix-cell w' + w }, String(w));
            inp.addEventListener('click', function() {
                const nw = (parseInt(inp.textContent) + 1) % 4;
                inp.textContent = nw; inp.className = 'matrix-cell w' + nw;
                if (!psoMatrixWeights[co.id]) psoMatrixWeights[co.id] = {};
                psoMatrixWeights[co.id][pso.id] = nw;
            });
            cell.appendChild(inp); row.appendChild(cell);
        });
        tbl.appendChild(row);
    });
    wrap.appendChild(tbl);
    const saveBtn = ac('button', { class: 'btn-sm btn-save', style: { marginTop: '14px' } }, 'Save CO-PSO Mapping');
    saveBtn.addEventListener('click', async function() {
        await adminApi('PUT', '/admin/copso', { courseId: courseId, weights: psoMatrixWeights });
        adminMsg('CO-PSO mapping saved!');
    });
    wrap.appendChild(saveBtn);
    card.appendChild(wrap);
}