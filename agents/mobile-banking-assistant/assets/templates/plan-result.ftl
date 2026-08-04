<#assign balance = capabilities["cap.account.balance.query"]!{}>
<#assign fund = capabilities["cap.fund.product.query"]!{}>
<#if balance.accountAlias?? && balance.availableBalance?? && fund.name?? && fund.riskLevel?? && fund.returnRate?? && fund.term??>两项查询已完成：
1. ${balance.accountAlias}可用余额为 ${balance.availableBalance} 元。
2. ${fund.name}风险等级 ${fund.riskLevel}，参考收益 ${fund.returnRate}，${fund.term}。<#else>已完成 ${completedCount} 项：
<#list completedSummaries as summary>${summary?counter}. ${summary}<#sep>
</#list></#if>
