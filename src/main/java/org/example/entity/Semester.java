package org.example.entity;

import jakarta.persistence.*;
import lombok.*;


@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Semester {
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;


private Integer number; // 1–8

    @ManyToOne
    private Batch batch;
}