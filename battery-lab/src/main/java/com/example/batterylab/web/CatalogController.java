package com.example.batterylab.web;

import com.example.batterylab.domain.DeviceProfile;
import com.example.batterylab.domain.ProtocolVersion;
import com.example.batterylab.domain.RuleVersion;
import com.example.batterylab.repo.DeviceProfileRepository;
import com.example.batterylab.repo.ProtocolVersionRepository;
import com.example.batterylab.repo.RuleVersionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final ProtocolVersionRepository protocols;
    private final DeviceProfileRepository profiles;
    private final RuleVersionRepository rules;

    public CatalogController(ProtocolVersionRepository protocols,
                             DeviceProfileRepository profiles, RuleVersionRepository rules) {
        this.protocols = protocols;
        this.profiles = profiles;
        this.rules = rules;
    }

    @GetMapping
    public Map<String, List<?>> catalog() {
        return Map.of("protocols", protocols.findAllByOrderByIdAsc(),
                "deviceProfiles", profiles.findAllByOrderByIdAsc(),
                "rules", rules.findAllByOrderByIdAsc());
    }
}
