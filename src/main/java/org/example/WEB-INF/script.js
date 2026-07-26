// ═══════════════════════════════════════════════════════════════
// ENV SWITCH  — only change the ONE line marked below
// ───────────────────────────────────────────────────────────────
//  ✅ LOCAL  : LOCAL_MODE = true   → hits http://localhost:8080
//  🚀 PROD   : LOCAL_MODE = false  → hits Render backend URL
// ───────────────────────────────────────────────────────────────

const LOCAL_MODE = true; // ← ✅ CHANGE THIS: true = local, false = production

// ── 🚀 PRODUCTION URL — only used when LOCAL_MODE = false ──────
const PROD_URL = 'https://obe-backend-qf77.onrender.com';
// ── END PROD URL ───────────────────────────────────────────────

// Resolves to '' (relative) when on port 8080, or full localhost URL
// when using VS Code Live Server on port 5500
const API = LOCAL_MODE
    ? (window.location.port === '8080' ? '' : 'http://localhost:8080')
    : PROD_URL;

// ═══════════════════════════════════════════════════════════════

// ═══ STATE ════════════════════════════════════════════════════
const S = { programId: null, batchYear: null, specId: null, semesterId: null, courseFilter: null, data: null };
let barChart = null, pieChart = null, selectedZip = null, selectedPdf = null, selectedStruct = null, selectedCmapFile = null, currentTab = 'dashboard';

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
async function switchTab(name, btn) {
    currentTab = name;
    document.querySelectorAll('.tab-btn').forEach(function (b) { b.classList.remove('active'); });
    if (btn) btn.classList.add('active');
    ['dashboard', 'mapping', 'students', 'upload', 'admin', 'verify'].forEach(function (v) {
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
    // Mapping tab: reload with includeEmpty=true so courses without marks are visible
    if (name === 'mapping') { await loadDashboard(); return; }
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
        batches.forEach(function (x) { b.add(new Option(x.label, x.year)); });
        b.disabled = batches.length === 0;
        // Auto-select the first batch
        if (batches.length > 0) {
            b.value = batches[0].year;
            S.batchYear = batches[0].year;
        }
    } catch (e) { console.warn('batches', e); }

    try {
        const specs = await get('/dashboard/specializations?programId=' + S.programId + (S.batchYear ? '&batchYear=' + S.batchYear : ''));
        const s = document.getElementById('sel_spec');
        specs.forEach(function (x) { s.add(new Option(x.name, x.id)); });
        s.disabled = specs.length === 0;
        if (specs.length === 0) await loadSems();
    } catch (e) { console.warn('specs', e); }
}

async function onBatchChange() {
    S.batchYear = document.getElementById('sel_batch').value || null;
    S.specId = null; S.semesterId = null; S.courseFilter = null;
    rst('sel_spec', '-- Select Specialization --');
    rst('sel_sem', '-- Select Semester --'); rst('sel_course_filter', '-- All Courses --');
    hideData();
    // Reload specializations filtered to the newly selected batch year
    try {
        const specs = await get('/dashboard/specializations?programId=' + S.programId + (S.batchYear ? '&batchYear=' + S.batchYear : ''));
        const s = document.getElementById('sel_spec');
        s.innerHTML = '<option value="">-- Select Specialization --</option>';
        specs.forEach(function (x) { s.add(new Option(x.name, x.id)); });
        s.disabled = specs.length === 0;
        if (specs.length === 0) await loadSems();
    } catch (e) { console.warn('specs on batch change', e); await loadSems(); }
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
        sems.forEach(function (x) { s.add(new Option(x.label, x.id)); });
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
        // Always fetch all courses including those without marks yet.
        // Dashboard view filters to studentCount>0 internally; student performance
        // dropdown needs all courses so UIUX/FSD/new batches show even before upload.
        coUrl += '&includeEmpty=true';
        const courseList = await get(coUrl);
        const filtered = S.courseFilter
            ? courseList.filter(function (c) { return c.code === S.courseFilter; })
            : courseList;
        const totalAllCourses = filtered.length;

        // ── Step 2: fetch CO, PO, PSO attainment + CO levels + at-risk for each course
        // Pass specializationId so attainment is computed from spec-filtered students only
        const attainments = await Promise.all(filtered.map(function (c) {
            const specParam = S.specId ? '?specializationId=' + S.specId : '';
            return Promise.all([
                get('/api/attainment/co/' + c.id + specParam).catch(function () { return {}; }),
                get('/api/attainment/po/' + c.id + specParam).catch(function () { return {}; }),
                get('/api/attainment/pso/' + c.id + specParam).catch(function () { return {}; }),
                get('/api/attainment/co-po-mapping/' + c.id).catch(function () { return []; }),
                get('/api/attainment/co-pso-mapping/' + c.id).catch(function () { return []; }),
                get('/api/attainment/co-levels/' + c.id + specParam).catch(function () { return {}; }),
                get('/api/attainment/at-risk/' + c.id + specParam).catch(function () { return { atRiskCount: 0 }; })
            ]).then(function (results) {
                return { co: results[0], po: results[1], pso: results[2], coPoMatrix: results[3], coPsoMatrix: results[4], coLevels: results[5], atRiskCount: results[6].atRiskCount || 0 };
            }).catch(function () {
                return { co: {}, po: {}, pso: {}, coPoMatrix: [], coPsoMatrix: [], coLevels: {}, atRiskCount: 0 };
            });
        }));

        // ── Step 3: build unified data structure ─────────────────
        let courses = filtered.map(function (c, idx) {
            const coMap = attainments[idx].co || {};
            const poMap = attainments[idx].po || {};
            const psoMap = attainments[idx].pso || {};
            const coLevelMap = attainments[idx].coLevels || {};
            const target = 40.0;

            const coAttainments = Object.entries(coMap).filter(function (e) { return e[1] > 0; }).map(function (e) {
                return {
                    co: e[0],
                    description: e[0],
                    attainment: Math.round(e[1] * 10) / 10,
                    level: coLevelMap[e[0]] != null ? coLevelMap[e[0]] : null,
                    target: target
                };
            }).sort(function (a, b) {
                var na = parseInt((a.co.match(/\d+$/) || [0])[0], 10);
                var nb = parseInt((b.co.match(/\d+$/) || [0])[0], 10);
                return na - nb;
            });
            const avg = coAttainments.length
                ? Math.round(coAttainments.reduce(function (s, co) { return s + co.attainment; }, 0) / coAttainments.length * 10) / 10
                : 0;

            // PO attainment: backend returns 0-3 decimal, keep as-is
            let poHeaders = Object.keys(poMap).sort(function (a, b) {
                var na = parseInt((a.match(/\d+$/) || [0])[0], 10);
                var nb = parseInt((b.match(/\d+$/) || [0])[0], 10);
                return na - nb;
            });
            const poAttainment = {};
            poHeaders.forEach(function (po) {
                poAttainment[po] = Math.round(poMap[po] * 100) / 100;
            });
            // ── MAPPING TAB FALLBACK ──────────────────────────────────────────
            // When there are no student marks (0 students), poMap is empty so
            // poHeaders = []. Derive PO column headers directly from the
            // coPoMatrix rows instead so the mapping table always renders.
            if (poHeaders.length === 0 && attainments[idx].coPoMatrix && attainments[idx].coPoMatrix.length > 0) {
                var poSet = {};
                attainments[idx].coPoMatrix.forEach(function (row) {
                    Object.keys(row).forEach(function (k) { if (k !== 'co') poSet[k] = true; });
                });
                poHeaders = Object.keys(poSet).sort(function (a, b) {
                    var na = parseInt((a.match(/\d+$/) || [0])[0], 10);
                    var nb = parseInt((b.match(/\d+$/) || [0])[0], 10);
                    return na - nb;
                });
            }
            // ── END MAPPING TAB FALLBACK ──────────────────────────────────────

            // PSO attainment: backend returns 0-3 decimal, keep as-is
            let psoHeaders = Object.keys(psoMap).sort(function (a, b) {
                var na = parseInt((a.match(/\d+$/) || [0])[0], 10);
                var nb = parseInt((b.match(/\d+$/) || [0])[0], 10);
                return na - nb;
            });
            const psoAttainment = {};
            psoHeaders.forEach(function (pso) {
                psoAttainment[pso] = Math.round(psoMap[pso] * 100) / 100;
            });
            // ── MAPPING TAB FALLBACK ──────────────────────────────────────────
            // Same fallback for PSO headers when no marks exist yet.
            if (psoHeaders.length === 0 && attainments[idx].coPsoMatrix && attainments[idx].coPsoMatrix.length > 0) {
                var psoSet = {};
                attainments[idx].coPsoMatrix.forEach(function (row) {
                    Object.keys(row).forEach(function (k) { if (k !== 'co') psoSet[k] = true; });
                });
                psoHeaders = Object.keys(psoSet).sort(function (a, b) {
                    var na = parseInt((a.match(/\d+$/) || [0])[0], 10);
                    var nb = parseInt((b.match(/\d+$/) || [0])[0], 10);
                    return na - nb;
                });
            }
            // ── END MAPPING TAB FALLBACK ──────────────────────────────────────

            return {
                id: c.id, courseCode: c.code, courseName: c.name,
                studentCount: c.studentCount != null ? c.studentCount : 0,
                avgAttainment: avg,
                coAttainments: coAttainments, coLevels: coLevelMap, examLoaded: false,
                poHeaders: poHeaders, poAttainment: poAttainment,
                psoHeaders: psoHeaders, psoAttainment: psoAttainment,
                atRiskCount: attainments[idx].atRiskCount || 0,
                coPoMatrix: attainments[idx].coPoMatrix || [], coPsoMatrix: attainments[idx].coPsoMatrix || []
            };
        });

        // allCourses: every course returned by the filter (used for CO-PO mapping tab).
        // Includes courses with CO-PO definitions but no marks yet uploaded.
        const allCourses = courses.slice();

        // dashboardCourses: only courses that have student marks — used for the
        // performance cards, KPI tiles, and Student Performance tab.
        const dashboardCourses = courses.filter(function (c) { return c.studentCount > 0; });

        const overallAtt = dashboardCourses.length
            ? Math.round(dashboardCourses.filter(function (c) { return c.avgAttainment > 0; })
                .reduce(function (s, c) { return s + c.avgAttainment; }, 0)
                / Math.max(1, dashboardCourses.filter(function (c) { return c.avgAttainment > 0; }).length) * 10) / 10
            : 0;

        // Collect PO/PSO attainments across courses with marks only
        const branchPoAtt = {}, branchPsoAtt = {};
        dashboardCourses.forEach(function (course) {
            Object.entries(course.poAttainment).forEach(function (e) {
                if (!branchPoAtt[e[0]]) branchPoAtt[e[0]] = [];
                branchPoAtt[e[0]].push(e[1]);
            });
            Object.entries(course.psoAttainment).forEach(function (e) {
                if (!branchPsoAtt[e[0]]) branchPsoAtt[e[0]] = [];
                branchPsoAtt[e[0]].push(e[1]);
            });
        });

        const branchPoAttainment = {}, branchPsoAttainment = {};
        Object.entries(branchPoAtt).forEach(function (e) {
            branchPoAttainment[e[0]] = Math.round(e[1].reduce(function (a, b) { return a + b; }, 0) / e[1].length * 10) / 10;
        });
        Object.entries(branchPsoAtt).forEach(function (e) {
            branchPsoAttainment[e[0]] = Math.round(e[1].reduce(function (a, b) { return a + b; }, 0) / e[1].length * 10) / 10;
        });

        // "Students Evaluated" = max across courses (students appear in multiple courses — don't sum)
        const totalStudents = dashboardCourses.reduce(function (max, c) { return Math.max(max, c.studentCount); }, 0);
        const totalAtRisk = dashboardCourses.reduce(function (max, c) { return Math.max(max, c.atRiskCount); }, 0);

        const coursesWithMarksCount = dashboardCourses.filter(function (c) { return c.coAttainments.length > 0; }).length;

        const d = {
            // courses: used for dashboard cards + Student Performance tab (marks only)
            courses: dashboardCourses,
            // allCourses: used for CO-PO mapping tab (includes courses without marks)
            allCourses: allCourses,
            totalCourses: coursesWithMarksCount,
            totalAllCourses: totalAllCourses,
            totalStudents: totalStudents,
            atRiskStudents: totalAtRisk,
            overallAttainment: overallAtt,
            branchPoAttainment: branchPoAttainment,
            branchPsoAttainment: branchPsoAttainment
        };
        S.data = d;
        renderDashboard(d);
        // Course filter dropdown: show courses with marks (for dashboard filter)
        // Student tab dropdown: use allCourses so BCS/MSC/MTech/BCA courses show up too
        fillCourseDropdowns(d.courses || [], d.allCourses || []);
        // Mapping tab: always use allCourses so CO-PO data shows even without marks
        if (currentTab === 'mapping') renderMapping(d.allCourses || []);
    } catch (e) { console.error('loadDashboard', e); }
}

// ═══ RENDER DASHBOARD ═════════════════════════════════════════
const COURSE_PALETTE = ['#3b5bdb', '#12b886', '#f59f00', '#7c3aed', '#e64747', '#0369a1', '#d97706', '#0891b2'];

function renderDashboard(d) {
    const courses = d.courses || [];
    // KPI: Courses with Marks as "X / Y"
    const coursesLabel = (d.totalCourses != null ? d.totalCourses : 0) + ' / ' + (d.totalAllCourses != null ? d.totalAllCourses : courses.length);
    document.getElementById('kpi-courses').textContent = coursesLabel;
    document.getElementById('kpi-students').textContent = d.totalStudents != null && d.totalStudents > 0 ? d.totalStudents : '—';
    // Count at-risk COs (Level 1 with attainment < 40%)
    const allCOsFlat = [];
    const courseColorMap = {};
    courses.forEach(function (c, ci) {
        courseColorMap[c.courseCode] = COURSE_PALETTE[ci % COURSE_PALETTE.length];
        (c.coAttainments || []).forEach(function (co) {
            allCOsFlat.push(Object.assign({}, co, { course: c.courseCode, courseName: c.courseName, courseIdx: ci }));
        });
    });
    // Users requested to show number of at risk students here instead of COs.
    document.getElementById('kpi-risk').textContent = d.atRiskStudents != null ? d.atRiskStudents : '—';

    // ── Bar chart: one color per course, dashed line for target ────────
    if (barChart) barChart.destroy();
    var barWrap = document.getElementById('attainment-table-wrap');
    if (barWrap) barWrap.innerHTML = ''; // always clear stale content

    if (allCOsFlat.length) {
        const barColors = allCOsFlat.map(function (c) { return courseColorMap[c.course] || '#3b5bdb'; });
        barChart = new Chart(document.getElementById('barChart'), {
            type: 'bar',
            data: {
                labels: allCOsFlat.map(function (c) { return c.co; }),
                datasets: [
                    {
                        label: 'Attainment %',
                        data: allCOsFlat.map(function (c) { return c.attainment; }),
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
                            title: function (items) {
                                var co = allCOsFlat[items[0].dataIndex];
                                return co ? co.courseName + ' (' + co.course + ')' : '';
                            },
                            label: function (item) {
                                if (item.datasetIndex === 1) return 'Target: 40%';
                                var co = allCOsFlat[item.dataIndex];
                                var lv = co && co.level != null ? ' — Level ' + co.level : '';
                                return item.label + ': ' + item.raw + '%' + lv;
                            }
                        }
                    }
                },
                scales: {
                    y: { max: 100, min: 0, ticks: { callback: function (v) { return v + '%'; }, font: { size: 10 } }, grid: { color: 'rgba(0,0,0,0.05)' } },
                    x: { ticks: { font: { size: 10 }, maxRotation: 45 }, grid: { display: false } }
                }
            }
        });

        // Build course color legend below chart
        var legend = courses.filter(function (c) { return c.coAttainments && c.coAttainments.length > 0; }).map(function (c) {
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
        courses.forEach(function (c) {
            (c.poHeaders || []).forEach(function (p) { if (allPoHdrs.indexOf(p) < 0) allPoHdrs.push(p); });
            (c.psoHeaders || []).forEach(function (p) { if (allPsoHdrs.indexOf(p) < 0) allPsoHdrs.push(p); });
        });
        allPoHdrs.sort(function (a, b) { return parseInt((a.match(/\d+$/) || [0])[0], 10) - parseInt((b.match(/\d+$/) || [0])[0], 10); });
        allPsoHdrs.sort(function (a, b) { return parseInt((a.match(/\d+$/) || [0])[0], 10) - parseInt((b.match(/\d+$/) || [0])[0], 10); });
        var hasPo = allPoHdrs.length > 0, hasPso = allPsoHdrs.length > 0;
        var tRows = courses.filter(function (c) { return c.coAttainments && c.coAttainments.length > 0; }).map(function (c) {
            var poTds = allPoHdrs.map(function (p) {
                // Show '–' if this PO is not in the attainment map (course has no CO mapped to it)
                var mapped = c.poAttainment && Object.prototype.hasOwnProperty.call(c.poAttainment, p);
                if (!mapped) return '<td style="text-align:center;color:var(--text-3);font-size:11px">–</td>';
                var v = c.poAttainment[p];
                return '<td style="text-align:center;color:' + clr(v / 3 * 100) + ';font-weight:600;font-size:11px">' + v + '</td>';
            }).join('');
            var psoTds = allPsoHdrs.map(function (p) {
                var mapped = c.psoAttainment && Object.prototype.hasOwnProperty.call(c.psoAttainment, p);
                if (!mapped) return '<td style="text-align:center;color:var(--text-3);font-size:11px">–</td>';
                var v = c.psoAttainment[p];
                return '<td style="text-align:center;color:' + clr(v / 3 * 100) + ';font-weight:600;font-size:11px">' + v + '</td>';
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
                (hasPo ? allPoHdrs.map(function (p) { return '<th style="text-align:center;padding:5px 8px;background:#eff6ff;font-size:10px;font-weight:600;color:#3b5bdb">' + p + '</th>'; }).join('') : '') +
                (hasPso ? allPsoHdrs.map(function (p) { return '<th style="text-align:center;padding:5px 8px;background:#f5f3ff;font-size:10px;font-weight:600;color:#7c3aed">' + p + '</th>'; }).join('') : '') +
                '</tr></thead><tbody>' + tRows + '</tbody></table></div></div>';
        }
    }

    // ── Pie chart: categorize by CO Level (1, 2, 3) ───────────────
    var coLevelCounts = [0, 0, 0]; // [Level1, Level2, Level3]
    courses.forEach(function (c) {
        Object.values(c.coLevels || {}).forEach(function (lv) {
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
    list.innerHTML = courses.map(function (c, i) {
        const avg = c.avgAttainment != null ? c.avgAttainment : 0;
        const coRows = (c.coAttainments || []).map(function (co, j) {
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
        // Only show POs that are actually mapped (exist as keys in poAtt)
        const mappedPoHeaders = poHeaders.filter(function (po) { return Object.prototype.hasOwnProperty.call(poAtt, po); });
        const poRows = mappedPoHeaders.length > 0 ? '<div style="margin-top:16px;border-top:1px solid var(--border);padding-top:12px;"><div style="font-size:11px;font-weight:600;color:var(--text-3);margin-bottom:8px;text-transform:uppercase;letter-spacing:.4px;">PO Attainment <span style="font-weight:400;font-size:10px">(0–3 scale)</span></div>' +
            '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(80px,1fr));gap:6px;">' +
            mappedPoHeaders.map(function (po) {
                const v = poAtt[po];
                return '<div style="display:flex;align-items:center;gap:6px;font-size:12px;"><strong>' + po + ':</strong><span style="color:' + clr(v / 3 * 100) + ';font-weight:600">' + v + '</span></div>';
            }).join('') + '</div></div>' : '';
        const psoHeaders = c.psoHeaders || [];
        const psoAtt = c.psoAttainment || {};
        const mappedPsoHeaders = psoHeaders.filter(function (pso) { return Object.prototype.hasOwnProperty.call(psoAtt, pso); });
        const psoRows = mappedPsoHeaders.length > 0 ? '<div style="margin-top:12px;border-top:1px solid var(--border);padding-top:12px;"><div style="font-size:11px;font-weight:600;color:var(--text-3);margin-bottom:8px;text-transform:uppercase;letter-spacing:.4px;">PSO Attainment <span style="font-weight:400;font-size:10px">(0–3 scale)</span></div>' +
            '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(80px,1fr));gap:6px;">' +
            mappedPsoHeaders.map(function (pso) {
                const v = psoAtt[pso];
                return '<div style="display:flex;align-items:center;gap:6px;font-size:12px;"><strong>' + pso + ':</strong><span style="color:' + clr(v / 3 * 100) + ';font-weight:600">' + v + '</span></div>';
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
                (c.coAttainments || []).forEach(function (co, j) {
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
function renderMapping(courses) {
    const el = document.getElementById('mapping-content');
    if (!courses.length) { el.innerHTML = '<div style="text-align:center;padding:48px;color:var(--text-3)">No mapping data available.</div>'; return; }
    el.innerHTML = courses.map(function (c) {
        const pos = c.poHeaders || [];
        const matrix = c.coPoMatrix || [];
        const poAtt = c.poAttainment || {};
        const psos = c.psoHeaders || [];
        const psoMatrix = c.coPsoMatrix || [];
        const psoAtt = c.psoAttainment || {};

        // Build a lookup for PSO weights by CO code from the separate psoMatrix
        var psoByCoCode = {};
        (psoMatrix || []).forEach(function (r) { if (r.co) psoByCoCode[r.co] = r; });

        // Determine the list of COs to display — union of coPoMatrix and coPsoMatrix COs
        var coKeys = [];
        var coKeySet = {};
        matrix.forEach(function (r) { if (r.co && !coKeySet[r.co]) { coKeys.push(r.co); coKeySet[r.co] = true; } });
        (psoMatrix || []).forEach(function (r) { if (r.co && !coKeySet[r.co]) { coKeys.push(r.co); coKeySet[r.co] = true; } });

        // Build a lookup for PO weights by CO code
        var poByCoCode = {};
        matrix.forEach(function (r) { if (r.co) poByCoCode[r.co] = r; });

        // Combined header: CO | PO1 PO2 ... | PSO1 PSO2 ...
        var hasPo = pos.length > 0, hasPso = psos.length > 0;
        var phdr = pos.map(function (p) { return '<th style="background:#eff6ff;color:#3b5bdb">' + p + '</th>'; }).join('');
        var psoHdr = psos.map(function (p) { return '<th style="background:#f5f3ff;color:#7c3aed">' + p + '</th>'; }).join('');

        // Combined rows: each CO gets one row with PO weights + PSO weights
        var rows = coKeys.map(function (coCode) {
            var poRow = poByCoCode[coCode] || {};
            var psoRow = psoByCoCode[coCode] || {};
            var poCells = pos.map(function (p) { var w = poRow[p] || 0; return '<td><div class="score-pill s' + w + '">' + (w || '–') + '</div></td>'; }).join('');
            var psoCells = psos.map(function (ps) { var w = psoRow[ps] || 0; return '<td><div class="score-pill s' + w + '">' + (w || '–') + '</div></td>'; }).join('');
            return '<tr><td><strong>' + coCode + '</strong></td>' + poCells + psoCells + '</tr>';
        }).join('');

        // Attainment footer row
        var prow = pos.map(function (p) {
            var v = poAtt[p] != null ? poAtt[p] : 0;
            var pctV = v / 3 * 100;
            return '<td><strong style="color:' + clr(pctV) + '">' + v + '</strong></td>';
        }).join('');
        var psoAttRow = psos.map(function (p) {
            var v = psoAtt[p] != null ? psoAtt[p] : 0;
            var pctV = v / 3 * 100;
            return '<td><strong style="color:' + clr(pctV) + '">' + v + '</strong></td>';
        }).join('');

        var html = '<div class="data-card">' +
            '<div class="data-card-header">' +
            '<div class="data-card-title"><span class="course-code">' + c.courseCode + '</span>' + c.courseName + '</div>' +
            '<span style="font-size:12px;color:var(--text-3)">' + c.studentCount + ' students · Avg ' + pct(c.avgAttainment) + '</span>' +
            '</div>';

        if (hasPo || hasPso) {
            html += '<div style="padding:10px 18px 4px;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.5px;color:var(--text-3)">CO-PO' + (hasPso ? ' / CO-PSO' : '') + ' Mapping</div>' +
                '<div class="table-wrap"><table>' +
                '<thead><tr><th>CO</th>' + phdr + psoHdr + '</tr></thead>' +
                '<tbody>' + (rows || '<tr><td colspan="' + (pos.length + psos.length + 1) + '" style="text-align:center;color:var(--text-3);padding:16px">No mapping data.</td></tr>') +
                '<tr style="background:var(--bg)"><td><strong>Attainment</strong></td>' + prow + psoAttRow + '</tr>' +
                '</tbody></table></div>';
        }

        html += '</div>';
        return html;
    }).join('');
}

// ═══ COURSE DROPDOWNS ═════════════════════════════════════════
function fillCourseDropdowns(courses, allCourses) {
    const fsel = document.getElementById('sel_course_filter');
    const prev = fsel.value;
    fsel.innerHTML = '<option value="">-- All Courses --</option>';
    courses.forEach(function (c) { fsel.add(new Option(c.courseCode + ' – ' + c.courseName, c.courseCode)); });
    if (prev) fsel.value = prev;
    fsel.disabled = courses.length === 0;
    // Student tab dropdown: use allCourses (includes BCS/MSC/MTech/BCA courses)
    // so student performance works for all programmes, not just those with marks
    const studentCourses = allCourses && allCourses.length > 0 ? allCourses : courses;
    const ssel = document.getElementById('sel_course');
    ssel.innerHTML = '<option value="">-- Select a Course --</option>';
    studentCourses.forEach(function (c) { ssel.add(new Option(c.courseCode + ' – ' + c.courseName, c.id)); });
}

// ═══ STUDENTS ═════════════════════════════════════════════════
async function loadStudents() {
    const courseId = document.getElementById('sel_course').value;
    const el = document.getElementById('students-content');
    if (!courseId) { el.innerHTML = ''; return; }
    el.innerHTML = '<div style="text-align:center;padding:30px;color:var(--text-3)">Loading...</div>';
    try {
        // Use courseId for exact course entity match.
        // Pass specializationId so common courses (e.g. ENMA101) show only the
        // selected specialization's ~50 students instead of all 1123 BTech students.
        let url = '/dashboard/students?courseId=' + encodeURIComponent(courseId);
        if (S.specId) url += '&specializationId=' + S.specId;
        if (S.batchYear) url += '&batchYear=' + S.batchYear;

        const d = await get(url);
        if (d.error) { el.innerHTML = '<div style="color:var(--danger);padding:16px">' + d.error + '</div>'; return; }
        const students = d.students || [], coCodes = d.coCodes || [];
        if (!students.length) { el.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-3)"><i class="fas fa-user-slash" style="font-size:28px;opacity:.3;display:block;margin-bottom:12px"></i>No student data for this course.<br><small>Make sure marks Excel files are uploaded for this course.</small></div>'; return; }
        const coHdrs = coCodes.map(function (c) { return '<th>' + c + '</th>'; }).join('');
        const rows = students.map(function (s) {
            const coTds = coCodes.map(function (c) { const v = s[c] != null ? s[c] : 0; return '<td class="co-pct-cell ' + (v < 40 ? 'low' : '') + '">' + v + '%</td>'; }).join('');
            return '<tr><td style="font-family:var(--mono);font-size:12px">' + (s.enrollmentNo || '—') + '</td><td>' + (s.name || '—') + '</td>' + coTds + '</tr>';
        }).join('');
        el.innerHTML = '<div class="data-card"><div class="data-card-header"><div class="data-card-title"><i class="fas fa-users" style="color:var(--accent)"></i> ' + (d.courseCode || '') + ': ' + (d.courseName || '') + '</div><span style="font-size:12px;color:var(--text-3)">' + students.length + ' students</span></div><div class="table-wrap"><table><thead><tr><th>Enrollment</th><th>Name</th>' + coHdrs + '</tr></thead><tbody>' + rows + '</tbody></table></div></div>';
    } catch (e) { el.innerHTML = '<div style="color:var(--danger);padding:16px">Error: ' + e.message + '</div>'; }
}

// ═══ UPLOAD ═══════════════════════════════════════════════════
function onZipSelect(e) { selectedZip = e.target.files[0]; document.getElementById('zipLabel').textContent = selectedZip ? selectedZip.name : 'Click to select ZIP'; document.getElementById('zipName').textContent = selectedZip ? selectedZip.name : ''; document.getElementById('zipBtn').disabled = !selectedZip; }
function onPdfSelect(e) { selectedPdf = e.target.files[0]; document.getElementById('pdfLabel').textContent = selectedPdf ? selectedPdf.name : 'Click to select PDF'; document.getElementById('pdfName').textContent = selectedPdf ? selectedPdf.name : ''; document.getElementById('pdfBtn').disabled = !selectedPdf; }
function onStructSelect(e) { selectedStruct = e.target.files[0]; document.getElementById('structLabel').textContent = selectedStruct ? selectedStruct.name : 'Click to select .xlsx'; document.getElementById('structName').textContent = selectedStruct ? selectedStruct.name : ''; document.getElementById('structBtn').disabled = !selectedStruct; }
function onCmapSelect(e) { selectedCmapFile = e.target.files[0]; document.getElementById('cmapLabel').textContent = selectedCmapFile ? selectedCmapFile.name : 'Click or drag CO PO MAPPING SHEET Excel here'; document.getElementById('cmapName').textContent = selectedCmapFile ? selectedCmapFile.name : ''; document.getElementById('cmapBtn').disabled = !selectedCmapFile; }

var selectedSpecFile = null;
function onSpecFileSelect(e) {
    selectedSpecFile = e.target.files[0];
    document.getElementById('specLabel').textContent = selectedSpecFile ? selectedSpecFile.name : 'Click or drag enrolment .xlsx here';
    document.getElementById('specBtn').disabled = !selectedSpecFile;
}

async function uploadSpecialization() {
    if (!selectedSpecFile) return;
    var btn = document.getElementById('specBtn'), res = document.getElementById('specResult');
    btn.innerHTML = '<span class="spin"></span> Processing...'; btn.disabled = true; res.className = 'upload-result';
    try {
        var fd = new FormData(); fd.append('file', selectedSpecFile);
        var r = await fetch(API + '/students/assign-specialization', { method: 'POST', body: fd });
        var d = await r.json();
        if (d.error) { res.className = 'upload-result error'; res.innerHTML = d.error; return; }
        res.className = 'upload-result success';
        var specLines = Object.entries(d.updated_by_specialization || {}).map(function (e) {
            return '<br>&nbsp;&nbsp;• ' + e[0] + ': <strong>' + e[1] + '</strong> students';
        }).join('');
        res.innerHTML = '<strong>✓ Done!</strong><br>' +
            'Rows in file: <strong>' + d.total_rows_in_file + '</strong> | ' +
            'Students updated: <strong>' + d.students_updated + '</strong> | ' +
            'Not in DB: <strong>' + d.students_not_in_db + '</strong> | ' +
            'No spec match: <strong>' + d.rows_without_spec_match + '</strong><br>' +
            'Still without specialization: <strong>' + d.students_still_without_spec + '</strong>' +
            (specLines ? '<br><em>Updated per specialization:</em>' + specLines : '');
    } catch (e) { res.className = 'upload-result error'; res.innerHTML = e.message; }
    btn.innerHTML = '<i class="fas fa-tags"></i> Assign Specializations'; btn.disabled = false;
}

async function checkSpecStatus() {
    var res = document.getElementById('specResult');
    res.className = 'upload-result'; res.innerHTML = 'Checking...'; res.style.display = 'block';
    try {
        var d = await get('/students/specialization-status');
        var lines = (d.per_specialization || []).map(function (s) {
            return '<br>&nbsp;&nbsp;• ' + (s.name || '?') + ' [' + (s.programme || '?') + ']: <strong>' + s.students + '</strong> students';
        }).join('');
        res.className = 'upload-result ' + (d.without_specialization > 0 ? 'error' : 'success');
        res.innerHTML = '<strong>Status:</strong> ' +
            d.with_specialization + ' / ' + d.total_students + ' students have a specialization assigned.<br>' +
            '<strong>' + d.without_specialization + '</strong> still need assignment.' +
            (lines ? '<br><em>Per specialization:</em>' + lines : '');
    } catch (e) { res.className = 'upload-result error'; res.innerHTML = e.message; }
}

async function autoFixSpecFromCode() {
    var res = document.getElementById('specResult');
    res.className = 'upload-result'; res.innerHTML = '<span class="spin"></span> Auto-assigning specializations from enrollment codes...'; res.style.display = 'block';
    try {
        var r = await fetch(API + '/students/assign-spec-from-enrollment-code', { method: 'POST' });
        var d = await r.json();
        if (d.error) { res.className = 'upload-result error'; res.innerHTML = d.error; return; }
        var specLines = Object.entries(d.by_specialization || {}).map(function (e) {
            return '<br>&nbsp;&nbsp;• ' + e[0] + ': <strong>' + e[1] + '</strong> students';
        }).join('');
        res.className = 'upload-result success';
        res.innerHTML = '<strong>✓ Done!</strong><br>' +
            'Total null-spec: <strong>' + d.total_null_spec_students + '</strong> | ' +
            'Fixed by enrollment code: <strong>' + d.updated + '</strong> | ' +
            'Fixed (single-spec prog): <strong>' + (d.single_spec_programme_updated || 0) + '</strong> | ' +
            'No match (plain prog): <strong>' + d.skipped_no_code_match + '</strong>' +
            (specLines ? '<br><em>By specialization:</em>' + specLines : '');
    } catch (e) { res.className = 'upload-result error'; res.innerHTML = e.message; }
}

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

// ── ✅ LOCAL/🚀 PROD: Result Excel upload — hits /marks/upload-result-excel ──
// Change LOCAL_MODE at the top of this file to switch environments.
let selectedResultExcel = null;

// ── Question-Wise Marks Report upload — /marks/upload-qwise-report ───────────
// Use this for "Question wise marks entry report Odd sem.xlsx" (Sheet2 format)
// Component "Class Test" Freq 1 → mid_term | "End Term" → end_term
let selectedQwiseFile = null;

function onQwiseSelect(e) {
    selectedQwiseFile = e.target.files[0];
    const label = document.getElementById('qwiseLabel');
    const name  = document.getElementById('qwiseName');
    const btn   = document.getElementById('qwiseBtn');
    if (label) label.textContent = selectedQwiseFile ? selectedQwiseFile.name : 'Click or drag Question-Wise Report .xlsx here';
    if (name)  name.textContent  = selectedQwiseFile ? selectedQwiseFile.name : '';
    if (btn)   btn.disabled      = !selectedQwiseFile;
}

async function uploadQwiseReport() {
    if (!selectedQwiseFile) return;
    const btn = document.getElementById('qwiseBtn');
    const res = document.getElementById('qwiseResult');
    btn.innerHTML = '<span class="spin"></span> Uploading…'; btn.disabled = true;
    res.className = 'upload-result';
    res.innerHTML = '';
    try {
        const fd = new FormData();
        fd.append('file', selectedQwiseFile);
        const r = await fetch(API + '/marks/upload-qwise-report', { method: 'POST', body: fd });
        if (!r.ok) { const t = await r.text(); throw new Error(t || r.statusText); }
        const d = await r.json();
        res.className = 'upload-result success';
        res.innerHTML =
            '<strong>' + (d.status || 'SUCCESS') + '</strong> — ' + d.message + '<br>' +
            '📄 Sheet: <code>' + (d.sheet_processed || '—') + '</code><br>' +
            '📖 Rows read: <strong>' + (d.rows_read || 0).toLocaleString() + '</strong>' +
            ' &nbsp;|&nbsp; ⏭ Skipped: <strong>' + (d.rows_skipped || 0).toLocaleString() + '</strong>' +
            ' &nbsp;|&nbsp; ♻ Duplicates: <strong>' + (d.duplicates_skipped || 0).toLocaleString() + '</strong><br>' +
            '💾 Mark records saved: <strong>' + (d.mark_records_saved || 0).toLocaleString() + '</strong>' +
            ' across <strong>' + (d.courses_processed || 0) + '</strong> courses<br>' +
            '<small style="color:var(--text-2)">' + (d.note || '') + '</small>';
    } catch (e) {
        res.className = 'upload-result error';
        res.innerHTML = '❌ ' + (e.message || 'Upload failed');
    }
    btn.innerHTML = '<i class="fas fa-upload"></i> Upload Report'; btn.disabled = false;
}

// ── Single Mark Sheet upload — /marks/upload-single-excel ───────────────────

let selectedSingleExcel = null;

function onSingleExcelSelect(e) {
    selectedSingleExcel = e.target.files[0];
    const label = document.getElementById('singleExcelLabel');
    const name  = document.getElementById('singleExcelName');
    const btn   = document.getElementById('singleExcelBtn');
    if (label) label.textContent = selectedSingleExcel ? selectedSingleExcel.name : 'Click or drag mark sheet .xlsx here';
    if (name)  name.textContent  = selectedSingleExcel ? selectedSingleExcel.name : '';
    if (btn)   btn.disabled      = !selectedSingleExcel;
}

async function uploadSingleExcel() {
    if (!selectedSingleExcel) return;
    const btn = document.getElementById('singleExcelBtn');
    const res = document.getElementById('singleExcelResult');
    btn.innerHTML = '<span class="spin"></span> Uploading...'; btn.disabled = true;
    res.className = 'upload-result';
    try {
        const fd = new FormData();
        fd.append('file', selectedSingleExcel);
        const r = await fetch(API + '/marks/upload-single-excel', { method: 'POST', body: fd });
        if (!r.ok) { const t = await r.text(); throw new Error(t || r.statusText); }
        const d = await r.json();
        res.className = 'upload-result success';
        res.innerHTML =
            '<strong>' + (d.status || 'SUCCESS') + '</strong> — ' + d.message + '<br>' +
            '📄 File: <strong>' + (d.file || selectedSingleExcel.name) + '</strong><br>' +
            '📚 Course: <strong>' + (d.course || '—') + '</strong><br>' +
            '💾 Mark records saved: <strong>' + (d.mark_records_saved || 0).toLocaleString() + '</strong>';
    } catch (e) {
        res.className = 'upload-result error';
        res.innerHTML = '❌ ' + (e.message || 'Upload failed');
    }
    btn.innerHTML = '<i class="fas fa-upload"></i> Upload Mark Sheet'; btn.disabled = false;
}

// ── Result Excel upload — /marks/upload-result-excel ────────────────────────
function onResultExcelSelect(e) {

    selectedResultExcel = e.target.files[0];
    const label = document.getElementById('resultExcelLabel');
    const name  = document.getElementById('resultExcelName');
    const btn   = document.getElementById('resultExcelBtn');
    if (label) label.textContent = selectedResultExcel ? selectedResultExcel.name : 'Click or drag Result Excel .xlsx here';
    if (name)  name.textContent  = selectedResultExcel ? selectedResultExcel.name : '';
    if (btn)   btn.disabled      = !selectedResultExcel;
}

async function uploadResultExcel() {
    if (!selectedResultExcel) return;
    const btn = document.getElementById('resultExcelBtn');
    const res = document.getElementById('resultExcelResult');
    btn.innerHTML = '<span class="spin"></span> Uploading...'; btn.disabled = true;
    res.className = 'upload-result';
    try {
        const fd = new FormData();
        fd.append('file', selectedResultExcel);
        const r = await fetch(API + '/marks/upload-result-excel', { method: 'POST', body: fd });
        if (!r.ok) { const t = await r.text(); throw new Error(t || r.statusText); }
        const d = await r.json();
        res.className = 'upload-result success';
        res.innerHTML =
            '<strong>' + (d.status || 'SUCCESS') + '</strong> — ' + d.message + '<br>' +
            '📥 Rows read: <strong>' + (d.rows_read || 0).toLocaleString() + '</strong> &nbsp;|&nbsp; ' +
            '💾 Marks saved: <strong>' + (d.mark_records_saved || 0).toLocaleString() + '</strong> &nbsp;|&nbsp; ' +
            '🔁 Duplicates skipped: <strong>' + (d.duplicates_skipped || 0).toLocaleString() + '</strong><br>' +
            '🎓 Absent rows skipped: <strong>' + (d.rows_absent_skipped || 0).toLocaleString() + '</strong> &nbsp;|&nbsp; ' +
            '📚 Courses processed: <strong>' + (d.courses_processed || 0) + '</strong><br>' +
            '<small style="color:var(--text-2)">mid_term max: ' + (d.mid_term_max_marks || 20) +
            ' &nbsp;|&nbsp; end_term max: ' + (d.end_term_max_marks || 50) + '</small>' +
            (d.rows_no_course_skipped ? '<br><small style="color:var(--warn)">⚠ ' + d.rows_no_course_skipped + ' rows skipped (course not in DB)</small>' : '') +
            (d.errors && d.errors.length ? '<br><small style="color:#dc2626">Errors: ' + d.errors.length + '</small>' : '');
    } catch (e) {
        res.className = 'upload-result error';
        res.innerHTML = '❌ ' + (e.message || 'Upload failed');
    }
    btn.innerHTML = '<i class="fas fa-upload"></i> Upload Result Excel'; btn.disabled = false;
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

/** Pre-fill CO-PO mapping form fields — called from Quick Fill buttons */
function cmapQuickFill(prog, spec, startYear, endYear) {
    const progEl  = document.getElementById('cmapProgram');
    const specEl  = document.getElementById('cmapSpec');
    const syEl    = document.getElementById('cmapStartYear');
    const eyEl    = document.getElementById('cmapEndYear');
    if (progEl)  progEl.value  = prog;
    if (specEl)  specEl.value  = spec;
    if (syEl)    syEl.value    = startYear;
    if (eyEl)    eyEl.value    = endYear;
    // Scroll the file picker into view so user can drag the file
    const zone = document.getElementById('cmapZone');
    if (zone) zone.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

async function uploadCoPoMap() {

    if (!selectedCmapFile) return;
    const btn = document.getElementById('cmapBtn'), res = document.getElementById('cmapResult');
    const prog = document.getElementById('cmapProgram').value;
    const spec = document.getElementById('cmapSpec').value;
    const sYear = document.getElementById('cmapStartYear').value;
    const eYear = document.getElementById('cmapEndYear').value;
    if (!prog) { res.className = 'upload-result error'; res.innerHTML = 'Programme is required.'; return; }
    if (!sYear) { res.className = 'upload-result error'; res.innerHTML = 'Batch Start Year is required.'; return; }
    if (!eYear) { res.className = 'upload-result error'; res.innerHTML = 'Batch End Year is required.'; return; }
    btn.innerHTML = '<span class="spin"></span> Uploading...'; btn.disabled = true; res.className = 'upload-result';
    try {
        const fd = new FormData();
        fd.append('file', selectedCmapFile);
        fd.append('programName', prog);
        if (spec) fd.append('specializationName', spec);
        fd.append('startYear', sYear);
        fd.append('endYear', eYear);
        const r = await fetch(API + '/copomap/upload', { method: 'POST', body: fd });
        const d = await r.json();
        if (d.error || d.message) throw new Error(d.message || d.error);
        res.className = 'upload-result success';
        res.innerHTML = '<strong>✅ Done!</strong><br>' +
            d.program + ' · ' + (d.specialization || '—') + ' · Batch ' + d.batch + '<br>' +
            d.pos_upserted + ' POs · ' + d.psos_upserted + ' PSOs<br>' +
            d.courses_created_or_found + ' courses · ' + d.cos_created + ' COs<br>' +
            d.copo_mappings_upserted + ' CO-PO mappings · ' + d.copso_mappings_upserted + ' CO-PSO mappings';
    } catch (e) { res.className = 'upload-result error'; res.innerHTML = 'Error: ' + e.message; }
    btn.innerHTML = '<i class="fas fa-project-diagram"></i> Upload CO-PO Mapping'; btn.disabled = false;
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
        const keys = ['programmes', 'specializations', 'batches', 'semesters', 'courses', 'cos', 'pos', 'psos', 'copo_mappings', 'copso_mappings', 'students', 'marks'];
        let html = '<div class="kpi-grid" style="grid-template-columns:repeat(6,1fr);margin-bottom:20px">';
        keys.forEach(function (k) {
            html += '<div class="kpi" style="flex-direction:column;align-items:flex-start;gap:4px"><div class="kpi-val" style="font-size:18px">' + (stats[k] || 0).toLocaleString() + '</div><div class="kpi-label">' + k.replace(/_/g, ' ') + '</div></div>';
        });
        html += '</div>';
        const courses = d.courses || [];
        html += '<div class="data-card" style="margin-bottom:14px"><div class="data-card-header"><div class="data-card-title">Courses</div><span style="font-size:12px;color:var(--text-3)">' + courses.length + ' total</span></div><div class="table-wrap"><table><thead><tr><th>Code</th><th>Name</th><th>Sem</th><th>COs</th><th>CO-PO</th><th>CO-PSO</th><th>Marks</th><th>Students</th></tr></thead><tbody>';
        courses.forEach(function (c) {
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
        if (pos.length) pos.forEach(function (p) { html += '<tr><td><strong>' + p.code + '</strong></td><td style="font-size:12px">' + (p.description || '—') + '</td></tr>'; });
        else html += '<tr><td colspan="2" style="color:var(--text-3);text-align:center;padding:16px">No POs found</td></tr>';
        html += '</tbody></table></div></div>';
        html += '<div class="data-card"><div class="data-card-header"><div class="data-card-title">Programme Specific Outcomes (PSO)</div></div><div class="table-wrap"><table><thead><tr><th>Code</th><th>Description</th></tr></thead><tbody>';
        if (psos.length) psos.forEach(function (p) { html += '<tr><td><strong>' + p.code + '</strong></td><td style="font-size:12px">' + (p.description || '—') + '</td></tr>'; });
        else html += '<tr><td colspan="2" style="color:var(--text-3);text-align:center;padding:16px">No PSOs found</td></tr>';
        html += '</tbody></table></div></div></div>';
        el.innerHTML = html;
    } catch (e) { el.innerHTML = '<div style="color:var(--danger);padding:16px">Error: ' + e.message + '</div>'; }
}

function renderVerifyResult(d) {
    const el = document.getElementById('verify-content');
    const checks = d.checks || [];
    const passed = checks.filter(function (c) { return c.status === 'OK'; }).length;
    const failed = checks.filter(function (c) { return c.status === 'MISSING' || c.status === 'MISMATCH'; }).length;
    const warn = checks.filter(function (c) { return c.status === 'WARN'; }).length;
    const statusColor = { OK: 'var(--success)', MISSING: 'var(--danger)', MISMATCH: 'var(--warn)', WARN: 'var(--warn)' };
    let html = '<div style="display:flex;gap:12px;margin-bottom:16px">' +
        '<div class="kpi" style="flex:1"><div class="kpi-icon green"><i class="fas fa-check"></i></div><div><div class="kpi-val">' + passed + '</div><div class="kpi-label">Passed</div></div></div>' +
        '<div class="kpi" style="flex:1"><div class="kpi-icon red"><i class="fas fa-times"></i></div><div><div class="kpi-val">' + failed + '</div><div class="kpi-label">Failed</div></div></div>' +
        '<div class="kpi" style="flex:1"><div class="kpi-icon orange"><i class="fas fa-exclamation"></i></div><div><div class="kpi-val">' + warn + '</div><div class="kpi-label">Warnings</div></div></div></div>';
    html += '<div class="data-card"><div class="data-card-header"><div class="data-card-title">Verification Results</div></div><div class="table-wrap"><table><thead><tr><th style="text-align:left">Check</th><th>Expected</th><th>In DB</th><th>Status</th></tr></thead><tbody>';
    checks.forEach(function (c) {
        html += '<tr><td><strong>' + c.label + '</strong></td><td style="font-family:var(--mono);font-size:12px">' + (c.expected || '—') + '</td><td style="font-family:var(--mono);font-size:12px">' + (c.actual || '—') + '</td><td style="color:' + (statusColor[c.status] || 'inherit') + ';font-weight:600">' + c.status + '</td></tr>';
    });
    html += '</tbody></table></div></div>';
    el.innerHTML = html;
}

// ═══ HIDE DATA ════════════════════════════════════════════════
function hideData() {
    ['dashboard', 'mapping', 'students', 'verify'].forEach(function (v) {
        document.getElementById('view-' + v).classList.add('hidden');
    });
    document.getElementById('empty-state').classList.remove('hidden');
    document.getElementById('course-list').innerHTML = '';
    document.getElementById('mapping-content').innerHTML = '';
    document.getElementById('students-content').innerHTML = '';
    document.getElementById('sel_course').innerHTML = '<option value="">-- Select a Course --</option>';
}

// ═══ INIT ═════════════════════════════════════════════════════
(async function () {
    try {
        const progs = await get('/dashboard/programs');
        const sel = document.getElementById('sel_prog');
        progs.forEach(function (p) { sel.add(new Option(p.name, p.id)); });
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
    document.querySelectorAll('.admin-nav-btn').forEach(function (b) { b.classList.remove('active'); });
    if (btn) btn.classList.add('active');
    ADM.section = sec;
    const map = {
        programs: loadAdminPrograms,
        specializations: loadAdminSpecializations,
        batches: loadAdminBatches,
        semesters: loadAdminSemesters,
        courses: loadAdminCourses,
        cos: loadAdminCOs,
        pos: loadAdminPOs,
        psos: loadAdminPSOs,
        copo: loadAdminCoPo,
        copso: loadAdminCoPso
    };
    if (map[sec]) map[sec]();
}

function ac(tag, props) {
    const el = document.createElement(tag);
    const children = Array.prototype.slice.call(arguments, 2);
    Object.entries(props || {}).forEach(function (entry) {
        const k = entry[0], v = entry[1];
        if (k === 'style') Object.assign(el.style, v);
        else if (k === 'class') el.className = v;
        else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v);
    });
    children.forEach(function (c) { el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return el;
}

function adminWrap(title) {
    const nodes = Array.prototype.slice.call(arguments, 1);
    const el = document.getElementById('admin-content');
    el.innerHTML = '';
    const card = ac('div', { class: 'data-card', style: { padding: '20px' } });
    card.appendChild(ac('h3', { style: { marginBottom: '16px', fontSize: '16px', color: 'var(--accent)' } }, title));
    nodes.forEach(function (n) { card.appendChild(n); });
    el.appendChild(card);
}

function adminMsg(txt, ok) {
    if (ok === undefined) ok = true;
    const m = ac('div', { style: { padding: '8px 12px', borderRadius: '7px', fontSize: '12px', fontWeight: 600, marginTop: '10px', background: ok ? '#f0fdf4' : '#fef2f2', color: ok ? '#166534' : '#991b1b' } }, txt);
    document.getElementById('admin-content').querySelectorAll('.admin-msg').forEach(function (x) { x.remove(); });
    m.className = 'admin-msg';
    const card = document.getElementById('admin-content').querySelector('.data-card');
    if (card) card.appendChild(m);
    setTimeout(function () { m.remove(); }, 3000);
}

function entityTable(cols, rows, actions) {
    const tbl = ac('table', { style: { width: '100%', borderCollapse: 'collapse', fontSize: '13px' } });
    const thead = ac('thead'), hr = ac('tr');
    cols.concat(['Actions']).forEach(function (c) { hr.appendChild(ac('th', { style: { textAlign: 'left', padding: '8px 10px', borderBottom: '2px solid var(--border)', color: 'var(--text-3)', fontSize: '11px', textTransform: 'uppercase' } }, c)); });
    thead.appendChild(hr); tbl.appendChild(thead);
    const tbody = ac('tbody');
    rows.forEach(function (row) {
        const tr = ac('tr');
        cols.forEach(function (c) { tr.appendChild(ac('td', { style: { padding: '8px 10px', borderBottom: '1px solid var(--border)' } }, String(row[c] != null ? row[c] : '—'))); });
        const actTd = ac('td', { style: { padding: '8px 10px', borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap' } });
        actions(row).forEach(function (b) { actTd.appendChild(b); });
        tr.appendChild(actTd); tbody.appendChild(tr);
    });
    tbl.appendChild(tbody); return tbl;
}

function btn(label, cls, onclick) { return ac('button', { class: 'btn-sm ' + cls, style: { marginRight: '4px' }, onclick: onclick }, label); }

/* ─── SPECIALIZATIONS ──────────────────────────────────────────────────────── */
async function loadAdminSpecializations() {
    const results = await Promise.all([adminApi('GET', '/admin/specializations'), adminApi('GET', '/admin/programs')]);
    const specs = results[0], progs = results[1];
    const form = ac('div', { class: 'admin-form' });
    const nameI = ac('input', { type: 'text', placeholder: 'Specialization Name', style: { minWidth: '200px' } });
    const pSel = ac('select', {}); pSel.appendChild(ac('option', { value: '' }, '-- Program --'));
    progs.forEach(function (p) { pSel.appendChild(ac('option', { value: p.id }, p.name)); });
    form.appendChild(nameI); form.appendChild(pSel);
    form.appendChild(btn('Add', 'btn-save', async function () {
        if (!nameI.value.trim() || !pSel.value) return;
        await adminApi('POST', '/admin/specializations', { name: nameI.value.trim(), programId: pSel.value });
        nameI.value = ''; loadAdminSpecializations(); adminMsg('Added!');
    }));
    const tbl = entityTable(['id', 'name', 'programName'], specs, function (row) {
        return [
            btn('Edit', 'btn-edit', function () {
                const name = prompt('Specialization name:', row.name);
                if (name) adminApi('PUT', '/admin/specializations/' + row.id, { name: name }).then(function () { loadAdminSpecializations(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function () {
                if (!confirm('⚠️ Permanently delete specialization "' + row.name + '"?\n\nThis will PERMANENTLY DELETE all linked data:\n• All batches, semesters, and courses\n• All student marks and QCO mappings\n• All CO, PO, PSO data\n\nThis CANNOT be undone!')) return;
                await adminApi('DELETE', '/admin/specializations/' + row.id); loadAdminSpecializations(); adminMsg('Deleted.');
            })
        ];
    });
    adminWrap('Specializations', form, tbl);
}

async function loadAdminPrograms() {
    const progs = await adminApi('GET', '/admin/programs');
    const form = ac('div', { class: 'admin-form' });
    const inp = ac('input', { type: 'text', placeholder: 'Program name' });
    form.appendChild(inp);
    form.appendChild(btn('Add', 'btn-save', async function () {
        if (!inp.value.trim()) return;
        await adminApi('POST', '/admin/programs', { name: inp.value.trim() });
        inp.value = ''; loadAdminPrograms(); adminMsg('Added!');
    }));
    const tbl = entityTable(['id', 'name'], progs, function (row) {
        return [
            btn('Edit', 'btn-edit', function () {
                const name = prompt('New name:', row.name);
                if (name) adminApi('PUT', '/admin/programs/' + row.id, { name: name }).then(function () { loadAdminPrograms(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function () {
                if (!confirm('⚠️ Permanently delete program "' + row.name + '"?\n\nThis will PERMANENTLY DELETE:\n• All batches, semesters, and courses\n• All student marks and CO data\n• All POs and PSOs\n\nThis CANNOT be undone!')) return;
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
    progs.forEach(function (p) { pSel.appendChild(ac('option', { value: p.id }, p.name)); });
    const sYear = ac('input', { type: 'number', placeholder: 'Start Year', style: { width: '100px' } });
    const eYear = ac('input', { type: 'number', placeholder: 'End Year', style: { width: '100px' } });
    form.appendChild(pSel); form.appendChild(sYear); form.appendChild(eYear);
    form.appendChild(btn('Add', 'btn-save', async function () {
        if (!pSel.value || !sYear.value) return;
        await adminApi('POST', '/admin/batches', { programId: pSel.value, startYear: sYear.value, endYear: eYear.value });
        sYear.value = ''; eYear.value = ''; loadAdminBatches(); adminMsg('Added!');
    }));
    const tbl = entityTable(['id', 'label', 'programName'], batches, function (row) {
        return [
            btn('Edit', 'btn-edit', function () {
                const sy = prompt('Start year:', row.startYear), ey = prompt('End year:', row.endYear);
                if (sy) adminApi('PUT', '/admin/batches/' + row.id, { startYear: sy, endYear: ey }).then(function () { loadAdminBatches(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function () {
                if (!confirm('⚠️ Permanently delete this batch?\n\nThis will PERMANENTLY DELETE all its semesters, courses, marks, and CO data.\n\nThis CANNOT be undone!')) return;
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
    batches.forEach(function (b) { bSel.appendChild(ac('option', { value: b.id }, b.programName + ' ' + b.label)); });
    const num = ac('input', { type: 'number', placeholder: 'Semester No.', min: 1, max: 12, style: { width: '100px' } });
    form.appendChild(bSel); form.appendChild(num);
    form.appendChild(btn('Add', 'btn-save', async function () {
        if (!bSel.value || !num.value) return;
        await adminApi('POST', '/admin/semesters', { batchId: bSel.value, number: num.value });
        num.value = ''; loadAdminSemesters(); adminMsg('Added!');
    }));
    const disp = sems.map(function (s) { return Object.assign({}, s, { batchLabel: s.batchLabel || '—', label: 'Semester ' + s.number }); });
    const tbl = entityTable(['id', 'label', 'batchLabel'], disp, function (row) {
        return [
            btn('Edit', 'btn-edit', function () {
                const n = prompt('Semester number:', row.number);
                if (n) adminApi('PUT', '/admin/semesters/' + row.id, { number: n }).then(function () { loadAdminSemesters(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function () {
                if (!confirm('⚠️ Permanently delete this semester?\n\nThis will PERMANENTLY DELETE all its courses, student marks, QCO mappings, and CO data.\n\nThis CANNOT be undone!')) return;
                await adminApi('DELETE', '/admin/semesters/' + row.id); loadAdminSemesters(); adminMsg('Deleted.');
            })
        ];
    });
    adminWrap('Semesters', form, tbl);
}

async function loadAdminCourses() {
    const results = await Promise.all([
        adminApi('GET', '/admin/courses'),
        adminApi('GET', '/admin/semesters'),
        adminApi('GET', '/admin/programs'),
        adminApi('GET', '/admin/batches'),
        adminApi('GET', '/admin/specializations')
    ]);
    const courses = results[0], sems = results[1], progs = results[2], batches = results[3], allSpecs = results[4];
    const form = ac('div', { class: 'admin-form' });
    const codeI = ac('input', { type: 'text', placeholder: 'Code' });
    const nameI = ac('input', { type: 'text', placeholder: 'Course Name', style: { minWidth: '200px' } });
    const pSel = ac('select', {}); pSel.appendChild(ac('option', { value: '' }, '-- Program --'));
    progs.forEach(function (p) { pSel.appendChild(ac('option', { value: p.id }, p.name)); });
    const bSel = ac('select', {}); bSel.appendChild(ac('option', { value: '' }, '-- Batch --'));
    batches.forEach(function (b) { bSel.appendChild(ac('option', { value: b.id }, b.programName + ' ' + b.label)); });
    const sSel = ac('select', {}); sSel.appendChild(ac('option', { value: '' }, '-- Semester --'));
    sems.forEach(function (s) { sSel.appendChild(ac('option', { value: s.id }, 'Sem ' + s.number + ' (' + s.batchLabel + ')')); });
    form.appendChild(codeI); form.appendChild(nameI); form.appendChild(pSel); form.appendChild(bSel); form.appendChild(sSel);
    form.appendChild(btn('Add', 'btn-save', async function () {
        if (!codeI.value || !nameI.value) return;
        await adminApi('POST', '/admin/courses', { courseCode: codeI.value, courseName: nameI.value, programId: pSel.value, batchId: bSel.value, semesterId: sSel.value });
        codeI.value = ''; nameI.value = ''; loadAdminCourses(); adminMsg('Added!');
    }));
    // Enhanced table with Edit button that opens inline form for code, name, semester, and specialization
    const tbl = entityTable(['id', 'courseCode', 'courseName', 'programName', 'semesterNumber'], courses, function (row) {
        return [
            btn('Edit', 'btn-edit', function () {
                // Show edit modal as inline row
                const card = document.getElementById('admin-content').querySelector('.data-card');
                let editRow = card.querySelector('#edit-course-row');
                if (editRow) editRow.remove();
                editRow = ac('div', { id: 'edit-course-row', style: { background: '#f0f9ff', border: '1px solid #bae6fd', borderRadius: '8px', padding: '12px', marginBottom: '10px' } });
                const eCode = ac('input', { type: 'text', placeholder: 'Code', style: { marginRight: '6px', padding: '5px 8px', border: '1px solid #cbd5e1', borderRadius: '5px' } }); eCode.value = row.courseCode;
                const eName = ac('input', { type: 'text', placeholder: 'Name', style: { marginRight: '6px', padding: '5px 8px', border: '1px solid #cbd5e1', borderRadius: '5px', minWidth: '180px' } }); eName.value = row.courseName;
                const eSem = ac('select', { style: { marginRight: '6px', padding: '5px 8px', border: '1px solid #cbd5e1', borderRadius: '5px' } });
                eSem.appendChild(ac('option', { value: '' }, '-- Keep Semester --'));
                sems.forEach(function (s) {
                    const o = ac('option', { value: s.id }, 'Sem ' + s.number + ' (' + s.batchLabel + ')');
                    if (s.id == row.semesterId) o.selected = true;
                    eSem.appendChild(o);
                });
                const eSpec = ac('select', { style: { marginRight: '6px', padding: '5px 8px', border: '1px solid #cbd5e1', borderRadius: '5px' } });
                eSpec.appendChild(ac('option', { value: '' }, '-- Keep Specialization --'));
                // Filter specs to the course's program
                var courseSpecs = allSpecs.filter(function (s) { return !row.programName || s.programName === row.programName; });
                if (courseSpecs.length === 0) courseSpecs = allSpecs;
                courseSpecs.forEach(function (s) {
                    const o = ac('option', { value: s.id }, s.name + ' (' + s.programName + ')');
                    if (s.id == row.specializationId) o.selected = true;
                    eSpec.appendChild(o);
                });
                const saveBtn = btn('Save', 'btn-save', async function () {
                    const payload = { courseCode: eCode.value, courseName: eName.value };
                    if (eSem.value) payload.semesterId = eSem.value;
                    if (eSpec.value) payload.specializationId = eSpec.value;
                    await adminApi('PUT', '/admin/courses/' + row.id, payload);
                    editRow.remove(); loadAdminCourses(); adminMsg('Saved!');
                });
                const cancelBtn = btn('✕', 'btn-edit', function () { editRow.remove(); });
                editRow.appendChild(ac('strong', {}, 'Editing: ' + row.courseCode));
                editRow.appendChild(ac('br', {}));
                editRow.appendChild(eCode); editRow.appendChild(eName);
                editRow.appendChild(ac('span', { style: { marginRight: '6px', fontSize: '12px', color: '#64748b' } }, 'Semester:'));
                editRow.appendChild(eSem);
                editRow.appendChild(ac('span', { style: { marginRight: '6px', fontSize: '12px', color: '#64748b' } }, 'Spec:'));
                editRow.appendChild(eSpec);
                editRow.appendChild(saveBtn); editRow.appendChild(cancelBtn);
                card.insertBefore(editRow, card.firstChild.nextSibling);
            }),
            btn('Delete', 'btn-delete', async function () {
                if (!confirm('⚠️ Permanently delete course "' + row.courseCode + '"?\n\nThis will PERMANENTLY DELETE all student marks, QCO mappings, and CO data for this course.\n\nThis CANNOT be undone!')) return;
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
    courses.forEach(function (c) { cSel.appendChild(ac('option', { value: c.id }, c.courseCode + ' – ' + c.courseName)); });
    form.appendChild(cSel);
    form.appendChild(btn('Load COs', 'btn-save', function () { loadAdminCOsForCourse(cSel.value); }));
    const addForm = ac('div', { class: 'admin-form', style: { marginTop: '12px' } });
    const codeI = ac('input', { type: 'text', placeholder: 'CO Code (e.g. CO1)' });
    const descI = ac('input', { type: 'text', placeholder: 'Description', style: { minWidth: '250px' } });
    addForm.appendChild(codeI); addForm.appendChild(descI);
    addForm.appendChild(btn('Add CO', 'btn-save', async function () {
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
    const tbl = entityTable(['id', 'code', 'description', 'courseCode'], cos, function (row) {
        return [
            btn('Edit', 'btn-edit', function () {
                const code = prompt('CO Code:', row.code), desc = prompt('Description:', row.description);
                if (code) adminApi('PUT', '/admin/cos/' + row.id, { code: code, description: desc }).then(function () { loadAdminCOsForCourse(courseId); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function () {
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
    progs.forEach(function (p) { pSel.appendChild(ac('option', { value: p.id }, p.name)); });
    const codeI = ac('input', { type: 'text', placeholder: 'PO Code (e.g. PO1)' });
    const descI = ac('input', { type: 'text', placeholder: 'Description', style: { minWidth: '250px' } });
    form.appendChild(pSel); form.appendChild(codeI); form.appendChild(descI);
    form.appendChild(btn('Add PO', 'btn-save', async function () {
        if (!pSel.value || !codeI.value) return;
        await adminApi('POST', '/admin/pos', { programId: pSel.value, code: codeI.value, description: descI.value });
        codeI.value = ''; descI.value = ''; loadAdminPOs(); adminMsg('Added!');
    }));
    const tbl = entityTable(['id', 'code', 'description', 'programName'], pos, function (row) {
        return [
            btn('Edit', 'btn-edit', function () {
                const code = prompt('PO Code:', row.code), desc = prompt('Description:', row.description);
                if (code) adminApi('PUT', '/admin/pos/' + row.id, { code: code, description: desc }).then(function () { loadAdminPOs(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function () {
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
    progs.forEach(function (p) { pSel.appendChild(ac('option', { value: p.id }, p.name)); });
    const codeI = ac('input', { type: 'text', placeholder: 'PSO Code (e.g. PSO1)' });
    const descI = ac('input', { type: 'text', placeholder: 'Description', style: { minWidth: '250px' } });
    form.appendChild(pSel); form.appendChild(codeI); form.appendChild(descI);
    form.appendChild(btn('Add PSO', 'btn-save', async function () {
        if (!pSel.value || !codeI.value) return;
        await adminApi('POST', '/admin/psos', { programId: pSel.value, code: codeI.value, description: descI.value });
        codeI.value = ''; descI.value = ''; loadAdminPSOs(); adminMsg('Added!');
    }));
    const tbl = entityTable(['id', 'code', 'description', 'programName'], psos, function (row) {
        return [
            btn('Edit', 'btn-edit', function () {
                const code = prompt('PSO Code:', row.code), desc = prompt('Description:', row.description);
                if (code) adminApi('PUT', '/admin/psos/' + row.id, { code: code, description: desc }).then(function () { loadAdminPSOs(); adminMsg('Saved!'); });
            }),
            btn('Delete', 'btn-delete', async function () {
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
    courses.forEach(function (c) { cSel.appendChild(ac('option', { value: c.id }, c.courseCode + ' – ' + c.courseName)); });
    form.appendChild(cSel);
    form.appendChild(btn('Load Matrix', 'btn-save', function () { renderCoPoMatrix(cSel.value); }));
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
    pos.forEach(function (po) { const th = ac('th', { style: { minWidth: '38px', textAlign: 'center', fontSize: '11px', padding: '2px' } }, po.code); th.title = po.description || po.code; hrow.appendChild(th); });
    tbl.appendChild(hrow);
    cos.forEach(function (co) {
        const row = ac('tr');
        const labTd = ac('td', { style: { padding: '4px 8px', fontSize: '12px', fontWeight: 600 } }, co.code);
        labTd.title = co.description || co.code; row.appendChild(labTd);
        pos.forEach(function (po) {
            const w = (matrixWeights[co.id] || {})[po.id] || 0;
            const cell = ac('td', {});
            const inp = ac('button', { class: 'matrix-cell w' + w, 'data-co': co.id, 'data-po': po.id }, String(w));
            inp.addEventListener('click', function () {
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
    saveBtn.addEventListener('click', async function () {
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
    courses.forEach(function (c) { cSel.appendChild(ac('option', { value: c.id }, c.courseCode + ' – ' + c.courseName)); });
    form.appendChild(cSel);
    form.appendChild(btn('Load Matrix', 'btn-save', function () { renderCoPsoMatrix(cSel.value); }));
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
    psos.forEach(function (pso) { const th = ac('th', { style: { minWidth: '38px', textAlign: 'center', fontSize: '11px', padding: '2px' } }, pso.code); th.title = pso.description || pso.code; hrow.appendChild(th); });
    tbl.appendChild(hrow);
    cos.forEach(function (co) {
        const row = ac('tr');
        const labTd = ac('td', { style: { padding: '4px 8px', fontSize: '12px', fontWeight: 600 } }, co.code);
        labTd.title = co.description || co.code; row.appendChild(labTd);
        psos.forEach(function (pso) {
            const w = (psoMatrixWeights[co.id] || {})[pso.id] || 0;
            const cell = ac('td', {});
            const inp = ac('button', { class: 'matrix-cell w' + w }, String(w));
            inp.addEventListener('click', function () {
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
    saveBtn.addEventListener('click', async function () {
        await adminApi('PUT', '/admin/copso', { courseId: courseId, weights: psoMatrixWeights });
        adminMsg('CO-PSO mapping saved!');
    });
    wrap.appendChild(saveBtn);
    card.appendChild(wrap);
}