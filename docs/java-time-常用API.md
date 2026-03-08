# Java 时间 API 速查（简明）

本文档简要说明常用的 java.time API：LocalDateTime、LocalDate、LocalTime、DateTimeFormatter 和 ZonedDateTime，包含创建、解析、格式化、常用转换与注意事项，示例以 Java 代码片段给出。

---

## 1. LocalDateTime（不含时区的日期时间）

- 含义：表示不含时区的日期与时间，例如 `2026-03-08T14:30:00`。
- 常用场景：数据库字段、业务逻辑内部时间表示（不带时区）。

创建：

```java
LocalDateTime now = LocalDateTime.now();
LocalDateTime of = LocalDateTime.of(2023, 3, 8, 14, 30, 0);

// 解析
LocalDateTime parsed = LocalDateTime.parse("2023-03-08T14:30:00");

// 格式化
String s = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

// 转为带时区的瞬时时间
long epochMilli = now.toEpochSecond(ZoneOffset.UTC) * 1000L; // 需指定 ZoneOffset
// 或者
Instant instant = now.atZone(ZoneId.systemDefault()).toInstant();
long millis = instant.toEpochMilli();
```

常用方法：
- `plusDays()`, `minusHours()` 等
- `toLocalDate()`, `toLocalTime()`
- `atZone(ZoneId)` 把 LocalDateTime 视为指定时区的时间，得到 ZonedDateTime

注意：LocalDateTime 不包含时区信息，要转为绝对瞬时点（Instant）必须提供 ZoneId/ZoneOffset。

---

## 2. LocalDate（仅日期）

- 含义：只有日期部分，例如 `2026-03-08`。

创建与示例：

```java
LocalDate today = LocalDate.now();
LocalDate of = LocalDate.of(2023, 3, 8);
LocalDate parsed = LocalDate.parse("2023-03-08");
String s = today.format(DateTimeFormatter.ISO_LOCAL_DATE); // "2023-03-08"

// 和 LocalDateTime 互转
LocalDateTime startOfDay = today.atStartOfDay(); // 00:00
```

常用方法：`plusDays()`, `withYear()`, `isBefore()`, `isAfter()`。

---

## 3. LocalTime（仅时间）

- 含义：只有时间部分（时分秒），例如 `14:30:00`。

示例：

```java
LocalTime now = LocalTime.now();
LocalTime of = LocalTime.of(14, 30, 0);
LocalTime parsed = LocalTime.parse("14:30:00");
String s = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
```

常用方法：`withHour()`, `plusMinutes()`, `isBefore()` 等。

---

## 4. DateTimeFormatter（格式化与解析）

- 含义：用于格式化/解析日期时间的工具类，线程安全且不可变。

常用预定义格式器：
- `DateTimeFormatter.ISO_LOCAL_DATE_TIME`（例如 `2011-12-03T10:15:30`）
- `DateTimeFormatter.ISO_LOCAL_DATE`，`ISO_LOCAL_TIME`

自定义格式：

```java
DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
String str = LocalDateTime.now().format(fmt);
LocalDateTime dt = LocalDateTime.parse("2023-03-08 14:30:00", fmt);
```

注意：解析字符串时格式必须匹配，否则抛出 `DateTimeParseException`。可以使用 `Optional`/try-catch 做容错。

特殊：如果要解析包含时区的字符串（例如 `2023-03-08T14:30:00+08:00[Asia/Shanghai]`），可使用 `ZonedDateTime` + 对应的 `DateTimeFormatter` 或内置的 ISO 解析器。

---

## 5. ZonedDateTime（时区感知的日期时间）

- 含义：包含时区/时区规则的日期时间，表示一个具体的瞬时点与其在某个时区的本地表示。

创建与转换示例：

```java
// 当前系统时区时间
ZonedDateTime zNow = ZonedDateTime.now();
// 指定时区
ZonedDateTime sh = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));

// 把 LocalDateTime 视作某时区的时间
LocalDateTime ldt = LocalDateTime.of(2023, 3, 8, 14, 30);
ZonedDateTime z = ldt.atZone(ZoneId.of("Asia/Shanghai"));

// 时区间转换（保持瞬时点）
ZonedDateTime london = z.withZoneSameInstant(ZoneId.of("Europe/London"));
// 只改变本地时间但不改变字段值（少用）
ZonedDateTime changedLocal = z.withZoneSameLocal(ZoneId.of("Europe/London"));

// 转 Instant 或 epoch millis
Instant instant = z.toInstant();
long epochMilli = instant.toEpochMilli();
```

注意：带时区的字符串解析可以使用 `ZonedDateTime.parse(...)` 或带有 zone 的 `DateTimeFormatter`。

---

## 常见注意点与最佳实践

- 区分“本地表示”（LocalDateTime）和“瞬时点/绝对时间”（Instant / ZonedDateTime）。
- 若要跨时区通信或保存绝对时间，优先使用 Instant 或带时区（ZonedDateTime / OffsetDateTime）和 UTC 存储策略。
- DateTimeFormatter 是线程安全的，可作为静态常量复用。
- 遇到夏令时切换（DST）时，某些本地时间可能不存在或重复，使用 ZonedDateTime 可以正确处理这些情况。
- 不要用 java.util.Date/java.util.Calendar 的格式化/解析类；推荐使用 java.time 全家桶。

---

## 参考示例（一览）

```java
// 1. 本地时间 -> 带时区 Instant
LocalDateTime ldt = LocalDateTime.now();
Instant i = ldt.atZone(ZoneId.of("Asia/Shanghai")).toInstant();

// 2. 字符串解析并转换到另一时区
DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
LocalDateTime parsed = LocalDateTime.parse("2026-03-08 14:30:00", fmt);
ZonedDateTime z = parsed.atZone(ZoneId.of("Asia/Shanghai"));
ZonedDateTime toUtc = z.withZoneSameInstant(ZoneOffset.UTC);

// 3. 输出格式化
System.out.println(toUtc.format(DateTimeFormatter.ISO_INSTANT));
```

---

如果你需要，我可以：
- 把这份文档翻译成英文；
- 为常见场景（数据库存储、跨时区显示、前端传参）给出更详细示例和单元测试代码片段。


