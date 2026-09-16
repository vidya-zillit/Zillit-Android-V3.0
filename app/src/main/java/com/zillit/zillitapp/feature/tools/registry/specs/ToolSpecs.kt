package com.zillit.zillitapp.feature.tools.registry.specs

import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/**
 * Every tool the app knows, one entry per file.
 *
 * The only list. A tool is added by writing its own file and adding one line here — which is
 * the whole point of the split: a fifty-entry table in one file is a merge conflict on every
 * branch that touches any tool.
 */
val toolSpecs: List<ToolSpec> = listOf(
    AccountHubTool,
    AccountsTool,
    AdDashboardTool,
    AssetRegisterTool,
    BoxScheduleTool,
    BudgetBuilderTool,
    BudgetDepartmentTool,
    BudgetMainTool,
    CallSheetTool,
    CardExpensesTool,
    CashExpensesTool,
    CastingTool,
    CastingBackgroundTool,
    CastingMainTool,
    CateringTool,
    ConfidentialInfoTool,
    ContinuityTool,
    CostReportTool,
    CrewListTool,
    DealMemoTool,
    DistributionListTool,
    DocumentDistributionTool,
    DodTool,
    DriveTool,
    ESignatureTool,
    EmailTool,
    ExternalUsersTool,
    FormsAndSignatureTool,
    InfoTool,
    InvoicesTool,
    LocationTool,
    MapTool,
    PayrollTool,
    PermissionGridTool,
    PreProductionTool,
    ProductionTool,
    ProductionReportTool,
    PurchaseOrderTool,
    RecceTool,
    ReportsTool,
    SaPortalTool,
    ScheduleDistributionTool,
    ScriptDistributionTool,
    ScriptNotesTool,
    SidesTool,
    TimecardTool,
    TransportationTool,
    WardrobeTool,
    WardrobeBackgroundTool,
    WardrobeMainTool,
    WeatherTool,
)
