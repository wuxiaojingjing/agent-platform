package com.huawei.finance.simulator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BankingSystemsController {
    private final Map<String, Map<String, Object>> idempotent = new ConcurrentHashMap<>();

    @GetMapping("/accounts/{principal}/balances")
    public Map<String, Object> balances(@PathVariable String principal) {
        return Map.of("cards", List.of(
                Map.of("index", 1, "alias", "尾号 8821 借记卡", "availableBalance", "12,845.60"),
                Map.of("index", 2, "alias", "尾号 3344 借记卡", "availableBalance", "8,000.00"),
                Map.of("index", 3, "alias", "尾号 5566 信用卡", "availableBalance", "3,000.00")));
    }

    @GetMapping("/accounts/{principal}/transactions")
    public List<Map<String, Object>> transactions(@PathVariable String principal) {
        return List.of(
                Map.of("date", "2026-07-24", "description", "超市消费", "amount", "-128.50"),
                Map.of("date", "2026-07-23", "description", "工资入账", "amount", "+18,600.00"),
                Map.of("date", "2026-07-21", "description", "转账", "amount", "-2,000.00"));
    }

    @PostMapping("/transfers")
    public Map<String, Object> transfer(@RequestBody Map<String, Object> command) {
        return idempotent.computeIfAbsent(required(command, "idempotencyKey"), key -> Map.of(
                "payee", required(command, "payee"), "amount", required(command, "amount"),
                "fromAccount", String.valueOf(command.getOrDefault("fromAccount", "尾号 8821 借记卡")),
                "serialNo", serial("TR", key), "finishedAt", "2026-07-30T12:00:00Z"));
    }

    @GetMapping("/creditcards/{principal}/bill")
    public Map<String, Object> bill(@PathVariable String principal,
                                    @RequestParam String cardRef) {
        return Map.of("billAmount", "3,280.00", "dueDate", "2026-08-10",
                "cardRef", cardRef);
    }

    @PostMapping("/creditcards/repayments")
    public Map<String, Object> repay(@RequestBody Map<String, Object> command) {
        return operation("RP", command, Map.of("amount", required(command, "amount"), "cardTypeName", ""));
    }

    @PostMapping("/creditcards/replacements")
    public Map<String, Object> replace(@RequestBody Map<String, Object> command) {
        String type = "DEBIT".equals(command.get("cardType")) ? "借记卡" : "信用卡";
        return operation("RC", command, Map.of("amount", "", "cardTypeName", type));
    }

    @GetMapping("/wealth/{principal}/holdings")
    public Map<String, Object> holdings(@PathVariable String principal) {
        return Map.of("totalAsset", "86,300.00", "profit", "+2,145.30");
    }

    @GetMapping("/funds/products")
    public Map<String, Object> fund() {
        return product("F-C", "基金产品C", "基金", "R3", "3.2%", "开放式");
    }

    @GetMapping("/deposits/products")
    public Map<String, Object> deposit() {
        return product("D-A", "三年期定期存款", "存款", "R0", "2.15%", "3年");
    }

    @GetMapping("/loans/products")
    public Map<String, Object> loan() {
        return product("L-A", "个人消费贷款", "贷款", "R1", "以审批结果为准", "最长5年");
    }

    @GetMapping("/wealth/products")
    public Map<String, Object> wealthProduct(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String capabilityId) {
        if ("cap.wealth-product.product-b2.query".equals(capabilityId)) {
            return product("W-B2", "产品B2", "理财", "R3", "业绩比较基准3.0%-3.5%", "365天");
        }
        return product("W-B", "产品B", "理财", "R2", "业绩比较基准2.6%-3.1%", "180天");
    }

    @GetMapping("/payroll/{principal}/status")
    public Map<String, Object> payrollStatus(@PathVariable String principal) {
        return Map.of("status", "已到账", "lastArrivalDate", "2026-07-23", "employer", "示例代发单位");
    }

    @GetMapping("/insurance/products")
    public Map<String, Object> insurance() {
        return product("I-A", "产品A", "保险", "R3", "-", "终身");
    }

    private Map<String, Object> operation(String prefix, Map<String, Object> command,
                                          Map<String, Object> fields) {
        String key = required(command, "idempotencyKey");
        return idempotent.computeIfAbsent(key, ignored -> {
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>(fields);
            out.put("serialNo", serial(prefix, key));
            return Map.copyOf(out);
        });
    }

    private static Map<String, Object> product(String code, String name, String domain,
                                                String risk, String rate, String term) {
        return Map.of("productCode", code, "name", name, "domain", domain,
                "riskLevel", risk, "returnRate", rate, "term", term);
    }
    private static String required(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException("missing " + key);
        return String.valueOf(value);
    }
    private static String serial(String prefix, String key) {
        return prefix + Integer.toUnsignedString(key.hashCode(), 36).toUpperCase();
    }
}
