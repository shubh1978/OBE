package org.example.entity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "student_mark")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class StudentMark {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false)
    private String question;           // "Q1(a)"

    @Column(nullable = false)
    private Double marks;              // 7.5  (actual score)

    @Column(name = "max_marks", nullable = false)
    private Double maxMarks;           // 10.0 (max for this question)

    @Column(name = "exam_type", nullable = false)
    private String examType;           // "mid_term" | "end_term"

    @Column(name = "event_name", length = 500)
    private String eventName;

    @Column(name = "event_max_marks")
    private Double eventMaxMarks;      // total paper max (50.0, 20.0)

    private String period;             // "Semester-I"
}
