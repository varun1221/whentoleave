package dev.varun.forecast.api.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "service_setting")
public class ServiceSetting {

    @Id
    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "value", nullable = false)
    private String value;

    protected ServiceSetting() {}

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
