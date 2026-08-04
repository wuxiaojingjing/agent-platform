<#assign balance = capabilities["cap.account.balance.query"]!{}>
<#assign fund = capabilities["cap.fund.product.query"]!{}>
<#if balance.accountAlias?? && balance.availableBalance??>已查到${balance.accountAlias}可用余额为 ${balance.availableBalance} 元。<#elseif fund.name?? && fund.riskLevel?? && fund.returnRate?? && fund.term??>已查到${fund.name}风险等级 ${fund.riskLevel}，参考收益 ${fund.returnRate}，${fund.term}。<#else>已完成 ${completedCount} 项：${completedSummaries?join("、")}。</#if>
<#if remainingSummaries?seq_contains("查询基金产品C")>基金产品查询尚未完成，可继续办理下一项。<#else>${remainingSummaries?join("、")}尚未完成，可继续办理下一项。</#if>
