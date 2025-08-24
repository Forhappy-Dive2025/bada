package com.happy.bada;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@Service
public class CardsService {

    // ====== HTTP / JSON ======
    private final RestTemplate rt = new RestTemplate();
    private final ObjectMapper om = new ObjectMapper();

    // ====== 외부 API 기본 정보 ======
    private final String base = "https://www.badatime.com/DIVE";
    private final String key;
    private final ZoneId zone = ZoneId.of("Asia/Seoul");


    private final String openMeteoBase = "https://api.open-meteo.com/v1/forecast";

    // 부산(해운대 인근) 고정 좌표 – 프로토타입 고정
    private static final double BUSAN_LAT = 35.1595;
    private static final double BUSAN_LON = 129.1626;

    // ====== 로거 & 전용 I/O 풀 ======
    private static final Logger LOG = Logger.getLogger(CardsService.class.getName());
    private final ExecutorService ioPool = Executors.newFixedThreadPool(
        Math.max(4, Runtime.getRuntime().availableProcessors()),
        r -> {
            Thread t = new Thread(r);
            t.setName("ext-io-" + t.getId());
            t.setDaemon(true);
            return t;
        }
    );

    @Autowired
    public CardsService(@Value("${bada.api.key}") String key) {
        this.key =key;
        // JDK 11+ HttpClient (keep-alive, 타임아웃)
        HttpClient jdk = HttpClient.newBuilder()
                                   .version(HttpClient.Version.HTTP_1_1)
                                   .connectTimeout(Duration.ofSeconds(3))
                                   .build();

        JdkClientHttpRequestFactory jf = new JdkClientHttpRequestFactory(jdk);
        jf.setReadTimeout(Duration.ofSeconds(4));
        rt.setRequestFactory(jf);
    }

    private Optional<String> fetchVisibilityKmFromOpenMeteo(Optional<ZonedDateTime> nowOpt) {
        ZonedDateTime nowZ = nowOpt.orElse(ZonedDateTime.now(zone)).withZoneSameInstant(zone);

        String url = UriComponentsBuilder.fromHttpUrl(openMeteoBase)
                                         .queryParam("latitude", BUSAN_LAT)
                                         .queryParam("longitude", BUSAN_LON)
                                         .queryParam("hourly", "visibility")
                                         .queryParam("timezone", "auto")               // ✅ 가장 안전
                                         .queryParam("past_days", 1)                   // 직전 시간 포함
                                         .queryParam("forecast_days", 1)               // 금일+가까운 미래
                                         .build(true)                                  // 인코딩 보존
                                         .toUriString();

        long t0 = System.currentTimeMillis();
        JsonNode root = getJson(url);                         // 여기서 4xx면 예외 → 바로 원인 확인 가능
        long t1 = System.currentTimeMillis();

        JsonNode hourly = root.path("hourly");
        if (hourly.isMissingNode()) {
            return Optional.empty();
        }

        JsonNode times = hourly.path("time");        // ["2025-08-23T22:00", ...]
        JsonNode visArr = hourly.path("visibility"); // [8000, 10000, ...] (meters)
        if (!times.isArray() || !visArr.isArray() || times.size() == 0 || visArr.size() != times.size()) {
            return Optional.empty();
        }

        // now와 가장 가까운 인덱스 찾기
        int bestIdx = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < times.size(); i++) {
            String ts = times.get(i).asText();
            try {
                // timezone=auto 로 받았으니 로컬 시각 문자열 (offset 없음)
                LocalDateTime ldt = LocalDateTime.parse(ts); // e.g. 2025-08-23T22:00
                ZonedDateTime zdt = ldt.atZone(zone);
                long diff = Math.abs(Duration.between(nowZ, zdt).toMinutes());
                if (diff < bestDiff) { bestDiff = diff; bestIdx = i; }
            } catch (Exception ex) {
            }
        }

        double meters = visArr.get(bestIdx).asDouble(Double.NaN);
        if (Double.isNaN(meters)) {
            return Optional.empty();
        }

        double km = meters / 1000.0;
        String out = String.format(Locale.US, "%.1f", km);
        return Optional.of(out);
    }



    // ===================== Public APIs (5 sets) =====================
    public CardsResponse5 getFishing(double lat, double lon, Optional<ZonedDateTime> nowOpt) {
        var ctx = loadContext(lat, lon, nowOpt);

        var set1 = new SetItem("평균 파도 높이", List.of(nvl(ctx.waveHeight,"-"), "M"));
        var set2 = new SetItem("현재 수온", List.of(nvl(ctx.waterTemp,"-"), "°C"));
        var set3 = new SetItem("잡히는 물고기", List.of("숭어"));
        var set4 = new SetItem("바다의 속도", List.of(nvl(ctx.windSpd,"-"), "m/s"));
        var set5 = ctx.nextTide.map(nt -> new SetItem(
            "오늘의 물돌이",
            // 기준 시각을 이벤트가 아니라 '현재 시각(nowKst)'으로 표기하도록 loadContext에서 전달
            List.of(
                toHHmm(nt.eventTime()),                         // ← API 이벤트 시각 그대로
                "%s까지 %d시간".formatted(nt.label(), nt.hoursLeft())
            )
        )).orElse(new SetItem("오늘의 물돌이", List.of("-", "-")));

        return new CardsResponse5(set1, set2, set3, set4, set5);
    }

    public CardsResponse5 getMudflat(double lat, double lon, Optional<ZonedDateTime> nowOpt) {
        var ctx = loadContext(lat, lon, nowOpt);

        var set1 = new SetItem("현재 수온", List.of(nvl(ctx.waterTemp,"-"), "°C"));

        var nextSunArr = ctx.nextSun
            .map(e -> List.of(e.getKey(), " " + e.getValue().toString()))
            .orElse(List.of("일몰/일출", " -"));
        var set2 = new SetItem("일몰일출", nextSunArr);

        var set3 = new SetItem("기상", List.of(nvl(ctx.skyText,"-"), nvl(joinTempWithUnit(ctx.airTemp)," -")));
        var set4 = new SetItem("바람", List.of(nvl(ctx.windSpd,"-"), nvl(ctx.windDir,"-")));

        var set5 = ctx.nextTide.map(nt -> new SetItem(
            "간조만조",
            List.of(
                "%s까지 %d시간 남았어요.".formatted(nt.label(), nt.hoursLeft()),
                formatMeters(nt.levelCm()),
                nt.label().equals("간조") ? "↓" : "↑",
                // ✅ 기준 시각을 '현재(nowKst)'로 표기
                ctx.nowAmpm + " 기준"
            )
        )).orElse(new SetItem("간조만조", List.of("정보 없음", "-", "↓", "-")));

        return new CardsResponse5(set1, set2, set3, set4, set5);
    }

    public CardsResponse5 getFisher(double lat, double lon, Optional<ZonedDateTime> nowOpt) {
        var ctx = loadContext(lat, lon, nowOpt);

        var set1 = new SetItem("바람", List.of(nvl(ctx.windSpd,"-"), nvl(ctx.windDir,"-")));
        var set2 = new SetItem("평균 파도 높이", List.of(nvl(ctx.waveHeight,"-"), "M"));
        var set3 = new SetItem("현재 수온", List.of(nvl(ctx.waterTemp,"-"), "°C"));
        var set4 = new SetItem("특보", List.of("강풍주의"));
        var set5 = ctx.nextTide.map(nt -> new SetItem(
            "간조만조",
            List.of(
                "%s까지 %d시간 남았어요.".formatted(nt.label(), nt.hoursLeft()),
                formatMeters(nt.levelCm()),
                nt.label().equals("간조") ? "↓" : "↑",
                // ✅ 현재(nowKst) 기준
                ctx.nowAmpm + " 기준"
            )
        )).orElse(new SetItem("간조만조", List.of("정보 없음", "-", "↓", "-")));

        return new CardsResponse5(set1, set2, set3, set4, set5);
    }

    public CardsResponse5 getShipping(double lat, double lon, Optional<ZonedDateTime> nowOpt) {
        var ctx = loadContext(lat, lon, nowOpt);

        var set1 = new SetItem("바람", List.of(nvl(ctx.windSpd,"-"), nvl(ctx.windDir,"-")));
        var set2 = new SetItem("평균 파도 높이", List.of(nvl(ctx.waveHeight,"-"), "M"));
        String visKm = fetchVisibilityKmFromOpenMeteo(nowOpt).orElse("-");
        var set3 = new SetItem("가시거리", List.of(visKm, "km"));        var set4 = new SetItem("특보", List.of("강풍주의"));
        var set5 = ctx.nextTide.map(nt -> new SetItem(
            "간조만조",
            List.of(
                "%s까지 %d시간 남았어요.".formatted(nt.label(), nt.hoursLeft()),
                formatMeters(nt.levelCm()),
                nt.label().equals("간조") ? "↓" : "↑",
                // ✅ 현재(nowKst) 기준
                ctx.nowAmpm + " 기준"
            )
        )).orElse(new SetItem("간조만조", List.of("정보 없음", "-", "↓", "-")));

        return new CardsResponse5(set1, set2, set3, set4, set5);
    }

    // ===================== Public APIs (6 sets) =====================
    public CardsResponse6 getSurfing(double lat, double lon, Optional<ZonedDateTime> nowOpt) {
        var ctx = loadContext(lat, lon, nowOpt);

        var set1 = new SetItem("평균 파도 높이", List.of(nvl(ctx.waveHeight,"-"), "M"));
        var set2 = new SetItem("바람", List.of(nvl(ctx.windSpd,"-"), nvl(ctx.windDir,"-")));
        var set3 = new SetItem("현재 수온", List.of(nvl(ctx.waterTemp,"-"), "°C"));
        var set4 = new SetItem("파도의 주기", List.of(nvl(ctx.wavePeriod,"-"), "초"));
        var set5 = new SetItem("파도의 방향", List.of(nvl(ctx.waveDir,"-"), arrowForDir(ctx.waveDir)));
        var set6 = new SetItem("파도의 속도", List.of(nvl(ctx.windSpd,"-"), "m/s")); // 별도 파속 없어서 바람으로 대체

        return new CardsResponse6(set1, set2, set3, set4, set5, set6);
    }

    public CardsResponse6 getSeaSwimming(double lat, double lon, Optional<ZonedDateTime> nowOpt) {
        var ctx = loadContext(lat, lon, nowOpt);

        var set1 = new SetItem("현재 수온", List.of(nvl(ctx.waterTemp,"-"), "°C"));
        var set2 = new SetItem("평균 파도 높이", List.of(nvl(ctx.waveHeight,"-"), "M"));
        var set3 = new SetItem("바람", List.of(nvl(ctx.windSpd,"-"), nvl(ctx.windDir,"-")));
        var set4 = new SetItem("특보", List.of("강풍주의"));
        var set5 = new SetItem("기상", List.of(nvl(ctx.skyText,"-"), nvl(ctx.airTemp,"-"))); // 예시 스펙대로 °C 미부착
        var set6 = new SetItem("파도의 속도", List.of(nvl(ctx.windSpd,"-"), "m/s"));

        return new CardsResponse6(set1, set2, set3, set4, set5, set6);
    }

    // ===================== 공통 컨텍스트 로딩 =====================
    private Context loadContext(double lat, double lon, Optional<ZonedDateTime> nowOpt) {
        long tAll0 = System.nanoTime();

        ZonedDateTime nowZ = nowOpt.orElse(ZonedDateTime.now(zone)).withZoneSameInstant(zone);
        LocalDate dateKst = nowZ.toLocalDate();
        LocalDateTime nowKst = nowZ.toLocalDateTime();

        // ---- 외부 호출 (병렬 + 개별 소요시간 로깅) ----
        var tideF = CompletableFuture.supplyAsync(
            () -> time("tide", () -> getJson("%s/tide?lat=%s&lon=%s&key=%s".formatted(base, lat, lon, key))),
            ioPool
        );
        var currentF = CompletableFuture.supplyAsync(
            () -> time("current", () -> getJson("%s/current?lat=%s&lon=%s&key=%s".formatted(base, lat, lon, key))),
            ioPool
        );
        var forecastF = CompletableFuture.supplyAsync(
            () -> time("forecast", () -> getJson("%s/forecast?lat=%s&lon=%s&key=%s".formatted(base, lat, lon, key))),
            ioPool
        );
        var tempF = CompletableFuture.supplyAsync(
            () -> time("temp", () -> getJson("%s/temp?lat=%s&lon=%s&key=%s".formatted(base, lat, lon, key))),
            ioPool
        );

        JsonNode tideArr     = tideF.join();
        JsonNode currentObj  = currentF.join();
        JsonNode forecastArr = forecastF.join();
        JsonNode tempArr     = tempF.join();

        // ---- tide: 오늘(로컬 KST) 항목 선택 ----
        JsonNode todayTide = pickTideForDate(tideArr, dateKst);
        String pSun = asText(todayTide, "pSun", null);
        var events = parseTideEvents(
            asText(todayTide, "pTime1", null),
            asText(todayTide, "pTime2", null),
            asText(todayTide, "pTime3", null),
            asText(todayTide, "pTime4", null)
        );
        var nextTide = nextTideInfo(events, nowKst);
        var nextSun  = nextSunK(pSun, nowKst);

        // ---- current: 최신 weather ----
        JsonNode weatherArr = currentObj.path("weather");
        JsonNode curPick = pickLatestByAplYmdt(weatherArr);
        String curSky = asText(curPick, "sky", null);
        String curTemp = asText(curPick, "temp", null);
        String curWindSpd = asText(curPick, "windspd", null);
        String curWindDir = asText(curPick, "winddir", null);
        String curPago = asText(curPick, "pago", null);

        // ---- forecast: now와 가장 가까운 1건 ----
        JsonNode fcPick = pickClosestForecast(forecastArr, nowKst);
        String fcSky = getLoose(fcPick, "sky");
        String fcTemp = getLoose(fcPick, "temp");
        String fcWindSpd = getLoose(fcPick, "windspd");
        String fcWindDir = getLoose(fcPick, "winddir");
        String fcWavePrd = getLoose(fcPick, "wavePrd");
        String fcWaveHt  = getLoose(fcPick, "waveHt");
        String fcWaveDir = getLoose(fcPick, "waveDir");

        // ---- temp: 가장 가까운 관측소 ----
        JsonNode tmpPick = pickNearestTemp(tempArr, lat, lon);
        String waterTemp = asText(tmpPick, "obs_wt", null);

        // ---- 값 합치기 ----
        String waveHeight = firstNonBlank(fcWaveHt, curPago);      // m
        String wavePeriod = fcWavePrd;                             // 초
        String waveDir    = fcWaveDir;
        String windSpd    = firstNonBlank(fcWindSpd, curWindSpd);  // m/s
        String windDir    = firstNonBlank(fcWindDir, curWindDir);
        String skyText    = firstNonBlank(fcSky, curSky);
        String airTemp    = firstNonBlank(fcTemp, curTemp);

        long allMs = (System.nanoTime() - tAll0) / 1_000_000;
        LOG.info(() -> "[ext] ALL external calls + parse took " + allMs + " ms");

        // ✅ nowKst 기준 문자열을 컨텍스트에 싣고, 카드에서 사용
        return new Context(
            waveHeight, wavePeriod, waveDir, windSpd, windDir, waterTemp, skyText, airTemp,
            nextTide, nextSun, ampmK(nowKst.toLocalTime())
        );
    }

    // ====== 타이머 래퍼 ======
    private <T> T time(String name, java.util.function.Supplier<T> call) {
        long t0 = System.nanoTime();
        try {
            return call.get();
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOG.info(() -> "[ext] " + name + " took " + ms + " ms");
        }
    }

    // ===================== HTTP & JSON helpers =====================
    private JsonNode getJson(String url) {
        String body = rt.getForObject(url, String.class);
        try {
            return om.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("JSON parse failed: " + url + " => " + body, e);
        }
    }

    private static String asText(JsonNode n, String key, String def) {
        if (n == null || n.isMissingNode()) return def;
        JsonNode v = n.get(key);
        return (v == null || v.isMissingNode() || v.isNull()) ? def : v.asText();
    }

    // 제로폭/이상 문자 들어간 키도 매칭: 영숫자만 남겨 비교
    private static String getLoose(JsonNode n, String targetKey) {
        if (n == null || n.isMissingNode()) return null;
        String want = onlyAlnum(targetKey).toLowerCase(Locale.ROOT);
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
            var e = it.next();
            String norm = onlyAlnum(e.getKey()).toLowerCase(Locale.ROOT);
            if (norm.equals(want)) return e.getValue().asText();
        }
        return null;
    }

    private static String onlyAlnum(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z0-9]", "");
    }

    // ===================== pickers (도메인 로직) =====================
    private static JsonNode pickTideForDate(JsonNode tideArr, LocalDate dateKst) {
        if (tideArr == null || !tideArr.isArray() || tideArr.isEmpty()) {
            return emptyObject();
        }
        for (JsonNode n : tideArr) {
            String raw = asText(n, "pThisDate", null);
            if (raw == null) continue;
            String[] tok = raw.split("-");
            if (tok.length < 3) continue;
            int y = parseInt(tok[0], -1);
            int m = parseInt(tok[1], -1);
            int d = parseInt(tok[2], -1);
            if (y == dateKst.getYear() && m == dateKst.getMonthValue() && d == dateKst.getDayOfMonth()) {
                return n;
            }
        }
        return tideArr.get(0);
    }

    private static JsonNode pickLatestByAplYmdt(JsonNode weatherArr) {
        if (weatherArr == null || !weatherArr.isArray() || weatherArr.isEmpty()) {
            return emptyObject();
        }
        JsonNode best = weatherArr.get(0);
        int bestNum = parseInt(asText(best, "aplYmdt", "0"), 0);
        for (JsonNode n : weatherArr) {
            int num = parseInt(asText(n, "aplYmdt", "0"), 0);
            if (num > bestNum) { best = n; bestNum = num; }
        }
        return best;
    }

    private static JsonNode pickClosestForecast(JsonNode forecastArr, LocalDateTime nowKst) {
        if (forecastArr == null || !forecastArr.isArray() || forecastArr.isEmpty()) {
            return emptyObject();
        }
        JsonNode best = forecastArr.get(0);
        long bestDiff = Long.MAX_VALUE;
        for (JsonNode n : forecastArr) {
            String ymdt = getLoose(n, "ymdt"); // "YYYYMMDDHH"
            if (ymdt == null || ymdt.length() < 10) continue;
            LocalDateTime t = parseYmdtHour(ymdt);
            long diff = Math.abs(Duration.between(nowKst, t).toMinutes());
            if (diff < bestDiff) { bestDiff = diff; best = n; }
        }
        return best;
    }

    private static JsonNode pickNearestTemp(JsonNode tempArr, double lat, double lon) {
        if (tempArr == null || !tempArr.isArray() || tempArr.isEmpty()) {
            return emptyObject();
        }
        JsonNode best = tempArr.get(0);
        double bestD = Double.MAX_VALUE;
        for (JsonNode n : tempArr) {
            double la = parseDouble(asText(n, "lat", null), Double.NaN);
            double lo = parseDouble(asText(n, "lon", null), Double.NaN);
            if (Double.isNaN(la) || Double.isNaN(lo)) continue;
            double d = haversineKm(lat, lon, la, lo);
            if (d < bestD) { bestD = d; best = n; }
        }
        return best;
    }

    // ===================== Tide parsing & time helpers =====================
    private static final Pattern LEVEL_EXTRACTOR = Pattern.compile(".*\\((\\d+)\\).*"); // (105) → 105

    private record TideEvent(LocalTime time, int levelCm) {}
    private static Optional<TideEvent> parseOneEvent(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            String hhmm = raw.split("\\s+")[0]; // "08:32"
            var m = LEVEL_EXTRACTOR.matcher(raw);
            if (!m.matches()) return Optional.empty();
            int level = Integer.parseInt(m.group(1));
            return Optional.of(new TideEvent(LocalTime.parse(hhmm), level));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
    private static List<TideEvent> parseTideEvents(String... raws) {
        List<TideEvent> list = new ArrayList<>();
        for (String r : raws) parseOneEvent(r).ifPresent(list::add);
        list.sort(Comparator.comparing(TideEvent::time));
        return list;
    }

    private record NextTide(String label, long hoursLeft, int levelCm, LocalTime eventTime) {}
    private static Optional<NextTide> nextTideInfo(List<TideEvent> events, LocalDateTime nowKst) {
        if (events.isEmpty()) return Optional.empty();
        int min = events.stream().mapToInt(TideEvent::levelCm).min().orElse(Integer.MIN_VALUE);
        int max = events.stream().mapToInt(TideEvent::levelCm).max().orElse(Integer.MAX_VALUE);

        LocalTime nowT = nowKst.toLocalTime();
        TideEvent next = events.stream()
                               .filter(e -> !e.time().isBefore(nowT))
                               .findFirst()
                               .orElse(events.get(0)); // 다음날 첫 이벤트

        String label = (next.levelCm() == max) ? "만조" : (next.levelCm() == min ? "간조" : "조석");
        long hours = Duration.between(nowT, next.time().isBefore(nowT) ? next.time().plusHours(24) : next.time()).toHours();
        if (hours < 0) hours += 24;
        return Optional.of(new NextTide(label, hours, next.levelCm(), next.time()));
    }

    private static Optional<Map.Entry<String, LocalTime>> nextSunK(String pSun, LocalDateTime nowKst) {
        if (pSun == null || !pSun.contains("/")) return Optional.empty();
        String[] ss = pSun.split("/");
        LocalTime sunrise = LocalTime.parse(ss[0].trim());
        LocalTime sunset  = LocalTime.parse(ss[1].trim());
        LocalTime nowT = nowKst.toLocalTime();
        if (!sunrise.isBefore(nowT)) return Optional.of(Map.entry("일출", sunrise));
        if (!sunset.isBefore(nowT))  return Optional.of(Map.entry("일몰", sunset));
        return Optional.of(Map.entry("일출", sunrise));
    }

    private static LocalDateTime parseYmdtHour(String ymdt) {
        int y = Integer.parseInt(ymdt.substring(0, 4));
        int m = Integer.parseInt(ymdt.substring(4, 6));
        int d = Integer.parseInt(ymdt.substring(6, 8));
        int h = Integer.parseInt(ymdt.substring(8, 10));
        return LocalDateTime.of(y, m, d, h, 0, 0);
    }

    // ===================== small utils =====================
    private static JsonNode emptyObject() { return new ObjectMapper().createObjectNode(); }
    private static String nvl(String s, String def) { return (s == null || s.isBlank()) ? def : s; }
    private static String firstNonBlank(String a, String b) { return (a != null && !a.isBlank()) ? a : ((b != null && !b.isBlank()) ? b : null); }
    private static String toHHmm(LocalTime t) { return String.format("%02d:%02d", t.getHour(), t.getMinute()); }
    private static String ampmK(LocalTime t) {
        int h = t.getHour();
        String ampm = h < 12 ? "오전" : "오후";
        int hh = h % 12; if (hh == 0) hh = 12;
        return "%s %02d:%02d".formatted(ampm, hh, t.getMinute());
    }
    private static String formatMeters(int levelCm) { return String.format(Locale.US, "%.1f", levelCm / 100.0); } // cm→m
    private static String joinTempWithUnit(String temp) { return (temp == null || temp.isBlank()) ? null : temp + "°C"; }

    private static String arrowForDir(String dir) {
        if (dir == null) return "•";
        String d = dir.toUpperCase(Locale.ROOT);
        if (d.startsWith("S")) return "↓";
        if (d.startsWith("N")) return "↑";
        if (d.startsWith("E")) return "→";
        if (d.startsWith("W")) return "←";
        return "•";
    }

    // 거리(km)
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
            + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    // ===================== 응답 DTO =====================
    public record SetItem(String type, Object data) {}
    public record CardsResponse5(SetItem set1, SetItem set2, SetItem set3, SetItem set4, SetItem set5) {}
    public record CardsResponse6(SetItem set1, SetItem set2, SetItem set3, SetItem set4, SetItem set5, SetItem set6) {}

    // ===================== 컨텍스트 =====================
    private record Context(
        String waveHeight, String wavePeriod, String waveDir,
        String windSpd, String windDir,
        String waterTemp, String skyText, String airTemp,
        Optional<NextTide> nextTide,
        Optional<Map.Entry<String, LocalTime>> nextSun,
        String nowAmpm // ✅ "오전/오후 hh:mm" 현재시각 표시용
    ) {}

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }
}
