package com.happy.bada;

import com.happy.bada.CardsService.CardsResponse5;  // ✅ 추가
import com.happy.bada.CardsService.CardsResponse6;  // ✅ 추가

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class CardsController {

    private final CardsService service;

    public CardsController(CardsService service) {
        this.service = service;
    }

    // ---- 5-set responses ----
    @GetMapping("/fishing")
    public CardsResponse5 fishing(
        @RequestParam double lat,
        @RequestParam double lon,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime now
    ) {
        return service.getFishing(lat, lon, Optional.ofNullable(now));
    }

    @GetMapping("/clam_digging")
    public CardsResponse5 clamDigging(
        @RequestParam double lat,
        @RequestParam double lon,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime now
    ) {
        return service.getMudflat(lat, lon, Optional.ofNullable(now));
    }

    @GetMapping("/fisher")
    public CardsResponse5 fisher(
        @RequestParam double lat,
        @RequestParam double lon,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime now
    ) {
        return service.getFisher(lat, lon, Optional.ofNullable(now));
    }

    @GetMapping("/shipping")
    public CardsResponse5 shipping(
        @RequestParam double lat,
        @RequestParam double lon,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime now
    ) {
        return service.getShipping(lat, lon, Optional.ofNullable(now));
    }

    // ---- 6-set responses ----
    @GetMapping("/surfing")
    public CardsResponse6 surfing(
        @RequestParam double lat,
        @RequestParam double lon,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime now
    ) {
        return service.getSurfing(lat, lon, Optional.ofNullable(now));
    }

    @GetMapping("/sea_swimming")
    public CardsResponse6 seaSwimming(
        @RequestParam double lat,
        @RequestParam double lon,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime now
    ) {
        return service.getSeaSwimming(lat, lon, Optional.ofNullable(now));
    }
}
