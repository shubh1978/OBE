package org.example.entity;

import jakarta.persistence.*;
import lombok.*;


@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Batch {
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;


private Integer startYear;
private Integer endYear;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

@ManyToOne
private Specialization specialization;
}