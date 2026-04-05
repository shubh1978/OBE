package org.example.entity;

import jakarta.persistence.*;
import lombok.*;


@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PSO {
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;


private String code; // PSO1, PSO2


@Column(length = 2000)
private String description;


@ManyToOne
private Specialization specialization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id")
    private Program program;
}