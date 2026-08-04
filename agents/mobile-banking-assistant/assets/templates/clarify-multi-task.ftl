${question}<#if taskSummaries?? && taskSummaries?size gt 0>
<#list taskSummaries as t>${t?counter}. ${t}
</#list></#if>
