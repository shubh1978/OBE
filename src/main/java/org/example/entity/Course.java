package org.example.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Course {
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;


private String courseCode;
private String courseName;


@ManyToOne
private Batch batch;
@ManyToOne private Program program;
@ManyToOne private Specialization specialization;
@ManyToOne private Semester semester;
}