/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.tools.odi.explorer.provider;

import java.util.List;
import java.util.Map;
import org.openmetadata.tools.odi.explorer.model.ContextInfo;
import org.openmetadata.tools.odi.explorer.model.DatastoreIdentity;
import org.openmetadata.tools.odi.explorer.model.LoadPlanDetail;
import org.openmetadata.tools.odi.explorer.model.LoadPlanStep;
import org.openmetadata.tools.odi.explorer.model.LoadPlanStepType;
import org.openmetadata.tools.odi.explorer.model.LoadPlanSummary;
import org.openmetadata.tools.odi.explorer.model.MappingColumn;
import org.openmetadata.tools.odi.explorer.model.MappingColumnLineage;
import org.openmetadata.tools.odi.explorer.model.MappingComponent;
import org.openmetadata.tools.odi.explorer.model.MappingDetail;
import org.openmetadata.tools.odi.explorer.model.MappingEdge;
import org.openmetadata.tools.odi.explorer.model.MappingReference;
import org.openmetadata.tools.odi.explorer.model.PhysicalLocation;
import org.openmetadata.tools.odi.explorer.model.RepositoryInfo;
import org.openmetadata.tools.odi.explorer.model.ScenarioReference;
import org.openmetadata.tools.odi.explorer.model.StepResolution;

public final class DemoOdiReadProvider implements OdiReadProvider {
  private static final String DEV_CONTEXT = "DEV";
  private static final String PROD_CONTEXT = "PROD";
  private static final String LOAD_PLAN_ID = "lp-sales";
  private static final String LOAD_PLAN_NAME = "Daily Sales Load";
  private static final String MAPPING_ID = "map-orders";
  private static final String MAPPING_NAME = "Load Orders";
  private static final String SALES_LOGICAL = "SALES_LOGICAL";
  private static final String DWH_LOGICAL = "DWH_LOGICAL";
  private static final RepositoryInfo REPOSITORY =
      new RepositoryInfo("ODI_DEMO", "MASTER", "WORKREP");
  private static final List<ContextInfo> CONTEXTS =
      List.of(
          new ContextInfo(DEV_CONTEXT, "Development", true),
          new ContextInfo(PROD_CONTEXT, "Production", false));
  private static final List<LoadPlanSummary> LOAD_PLANS =
      List.of(
          new LoadPlanSummary(
              LOAD_PLAN_ID, LOAD_PLAN_NAME, "Loads the daily sales warehouse model", 1, 1));
  private static final Map<String, Map<String, PhysicalLocation>> PHYSICAL_LOCATIONS =
      Map.of(
          DEV_CONTEXT,
          Map.of(
              SALES_LOGICAL,
              new PhysicalLocation("oracle-dev.SALES_DEV", "oracle-dev", "ODIPDB", "SALES_DEV"),
              DWH_LOGICAL,
              new PhysicalLocation("oracle-dev.DWH_DEV", "oracle-dev", "ODIPDB", "DWH_DEV")),
          PROD_CONTEXT,
          Map.of(
              SALES_LOGICAL,
              new PhysicalLocation("oracle-prod.SALES", "oracle-prod", "ODIPDB", "SALES"),
              DWH_LOGICAL,
              new PhysicalLocation("oracle-prod.DWH", "oracle-prod", "ODIPDB", "DWH")));

  @Override
  public RepositoryInfo repository() {
    return REPOSITORY;
  }

  @Override
  public List<ContextInfo> contexts() {
    return CONTEXTS;
  }

  @Override
  public List<LoadPlanSummary> loadPlans() {
    return LOAD_PLANS;
  }

  @Override
  public LoadPlanDetail loadPlan(String id, String contextCode) {
    requireIdentifier("Load plan", LOAD_PLAN_ID, id);
    requireContext(contextCode);
    final LoadPlanStep root =
        new LoadPlanStep(
            "root-step",
            null,
            "root_step",
            LoadPlanStepType.ROOT_SERIAL,
            List.of("root_step"),
            null,
            null,
            null,
            null,
            null,
            true);
    final LoadPlanStep parallel =
        new LoadPlanStep(
            "parallel-sales",
            "root-step",
            "parallel_sales",
            LoadPlanStepType.PARALLEL,
            List.of("root_step", "parallel_sales"),
            null,
            null,
            null,
            null,
            null,
            true);
    final LoadPlanStep step =
        new LoadPlanStep(
            "step-orders",
            "parallel-sales",
            MAPPING_NAME,
            LoadPlanStepType.RUN_SCENARIO,
            List.of("root_step", "parallel_sales", MAPPING_NAME),
            contextCode,
            new ScenarioReference("SCEN_LOAD_ORDERS", "001"),
            new MappingReference(MAPPING_ID, MAPPING_NAME),
            StepResolution.RESOLVED,
            null,
            true);
    return new LoadPlanDetail(
        LOAD_PLAN_ID, LOAD_PLAN_NAME, contextCode, List.of(root, parallel, step));
  }

  @Override
  public MappingDetail mapping(String id, String contextCode) {
    requireIdentifier("Mapping", MAPPING_ID, id);
    final Map<String, PhysicalLocation> locations = requireContext(contextCode);
    return new MappingDetail(
        MAPPING_ID,
        MAPPING_NAME,
        contextCode,
        mappingComponents(locations),
        List.of(new MappingEdge("orders-source", "sales-target")),
        List.of(
            new MappingColumnLineage(
                "orders-source",
                "orders-source::ORDER_ID",
                "sales-target",
                "sales-target::ORDER_ID"),
            new MappingColumnLineage(
                "orders-source",
                "orders-source::AMOUNT",
                "sales-target",
                "sales-target::TOTAL_AMOUNT")));
  }

  private List<MappingComponent> mappingComponents(Map<String, PhysicalLocation> locations) {
    return List.of(
        new MappingComponent(
            "orders-source",
            "DATASTORE_SOURCE",
            "ORDERS_SRC",
            new DatastoreIdentity(
                "Orders",
                "ORDERS",
                "Sales Source Model",
                SALES_LOGICAL,
                locations.get(SALES_LOGICAL),
                null),
            List.of(
                new MappingColumn("orders-source::ORDER_ID", "ORDER_ID"),
                new MappingColumn("orders-source::CUSTOMER_ID", "CUSTOMER_ID"),
                new MappingColumn("orders-source::AMOUNT", "AMOUNT"))),
        new MappingComponent(
            "sales-target",
            "DATASTORE_TARGET",
            "SALES_FACT_TGT",
            new DatastoreIdentity(
                "Sales Fact",
                "FCT_SALES",
                "Sales Warehouse Model",
                DWH_LOGICAL,
                locations.get(DWH_LOGICAL),
                null),
            List.of(
                new MappingColumn("sales-target::ORDER_ID", "ORDER_ID"),
                new MappingColumn("sales-target::CUSTOMER_KEY", "CUSTOMER_KEY"),
                new MappingColumn("sales-target::TOTAL_AMOUNT", "TOTAL_AMOUNT"))));
  }

  private Map<String, PhysicalLocation> requireContext(String contextCode) {
    final Map<String, PhysicalLocation> locations = PHYSICAL_LOCATIONS.get(contextCode);
    if (locations == null) {
      throw new ResourceNotFoundException("Context", contextCode);
    }
    return locations;
  }

  private void requireIdentifier(String resourceType, String expected, String actual) {
    if (!expected.equals(actual)) {
      throw new ResourceNotFoundException(resourceType, actual);
    }
  }
}
