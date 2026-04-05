package org.example.entity;

import jakarta.persistence.*;
import lombok.*;


@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Specialization {
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;


private String name; // CSE, AIML, CORE


@ManyToOne
private Program program;
}