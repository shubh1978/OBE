# 🎬 OBE Analytics System — Projexa Video Script
**Total Duration: ~3 minutes 30 seconds**

---

## 1. INTRODUCTION  *(30–45 seconds)*

> **[Look at camera. Smile. Speak clearly.]*

"Hi, I'm Shubh Singhal, and today I'll be presenting my project — the **OBE Analytics System** — built as part of my B.Tech curriculum.

This system is designed to automate **Outcome Based Education** attainment reporting for universities. In traditional systems, faculty have to manually calculate how well students are achieving Course Outcomes, Program Outcomes, and Program Specific Outcomes — a process that takes hours and is highly error-prone.

My project solves this by giving administrators a **real-time analytics dashboard** where all attainment metrics are calculated automatically from uploaded exam data."

---

## 2. PROJECT DESCRIPTION  *(45 seconds – 1 minute)*

> **[Transition to screen share / demo screen]*

**Motivation:**
"I chose this project because our institution follows the NBA accreditation framework, which mandates OBE reporting every semester. Faculty were spending enormous time on Excel-based manual calculations. I wanted to build a tool that makes this instant and accurate."

**Technologies Used:**
"The backend is built in **Java Spring Boot**, using **JPA with MySQL** for data persistence. The frontend is pure **HTML, CSS, and JavaScript** — no frameworks — which keeps it lightweight and deployable on any server. I've also used **Apache POI** for reading Excel files and **iText** for PDF parsing."

**Key Features:**
"Three features make this stand out:
1. **Automated mark ingestion** — upload a ZIP of Excel mark sheets and the system parses them automatically.
2. **Intelligent attainment calculation** — CO, PO, and PSO levels are computed using a standardized threshold-and-level-based formula.
3. **Live analytics dashboard** — with progress bars, charts, and attainment breakdowns per course."

---

## 3. DEMONSTRATION  *(1.5 – 2 minutes)*

> **[Screen recording of the live application at 127.0.0.1:5500]*

**Step 1 — Dashboard Overview:**
"Here's the main dashboard. I've selected B.Tech, batch 2024, Semester 1. You can instantly see all courses listed with their average CO attainment."

**Step 2 — CO Attainment Bars:**
"Let me open this course — Basics of Electrical and Electronics Engineering. You can see CO1 through CO5 displayed with progress bars. CO1 has 85.4% — meaning over 80% of students scored above the 40% threshold, giving it Level 3 attainment."

**Step 3 — PO and PSO:**
"Scrolling down, here are the PO and PSO attainment values. These are calculated using the formula: the CO attainment level — which is 0, 1, 2, or 3 — is multiplied by the mapping weight and divided by the total weights. The result is a decimal value on a 0-to-3 scale."

**Step 4 — Data Upload:**
"Now let me show you the upload flow. I drag and drop a ZIP of Excel mark sheets here. The system processes each file, matches it to the correct course already in the database, and rejects any file for an unrecognized course — preventing phantom data."

**Step 5 — Verify Tab:**
"The Verify tab gives a full health check of the database — showing how many courses, COs, POs, marks, and students are registered, and flagging anything that doesn't match."

---

## 4. CHALLENGES AND SOLUTIONS  *(30–45 seconds)*

> **[Look at camera or continue screen share]*

"I faced two major challenges during development.

**First**, CO code mismatches — the database stores codes like 'CO1', but Excel files sometimes have 'ENSP201-CO1'. I solved this using a **regex-based suffix extractor** that strips the course prefix and matches only the CO number.

**Second**, phantom data — marks were being created for courses that didn't exist in the uploaded structure file, because the system was auto-creating unknown courses. I fixed this by implementing **strict database validation** — if the program, batch, semester, and course don't already exist, the row is silently rejected with a warning log, keeping data clean."

---

## 5. CONCLUSION  *(20–30 seconds)*

> **[Look at camera. Confident close.]*

"In summary, the OBE Analytics System transforms a tedious, manual process into an automated, real-time reporting pipeline — saving faculty hours each semester and ensuring accurate accreditation data.

In the future, I plan to add **PDF report generation** so that attainment reports can be exported directly for NBA submission, and a **comparison view** to track attainment trends across semesters.

Thank you for watching!"

---

## ⏱️ Timing Reference

| Section | Target Time | Approx Words |
|---------|-------------|--------------|
| Introduction | 30–45 sec | ~90 words |
| Project Description | 45–60 sec | ~130 words |
| Demonstration | 90–120 sec | ~200 words |
| Challenges & Solutions | 30–45 sec | ~100 words |
| Conclusion | 20–30 sec | ~65 words |
| **Total** | **~3:30** | **~585 words** |

---

## 📋 Recording Tips

- Record screen at **1920×1080** if possible
- Use the **dashboard** with real data loaded before you start the demo
- Pre-open the correct semester (B.Tech → Sem 1) so you don't waste time on filters
- Speak at a **moderate pace** — don't rush the demo section
- The application URL to show: `127.0.0.1:5500/src/main/java/org/example/WEB-INF/index.html`
