package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.microsoft.graph.models.ColumnDefinition;
import com.microsoft.graph.models.ColumnDefinitionCollectionResponse;
import com.microsoft.graph.models.ListCollectionResponse;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.models.ListItemCollectionResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/lista-sharepoint")
public class SharepointListServlet extends HttpServlet {
    private static final long serialVersionUID = 2L; // Versión actualizada
    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doPost(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String siteSelection = request.getParameter("siteSelection");
        String listIdOrName = request.getParameter("lista");

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        String siteId = SharepointUtil.SITE_ID; // Por defecto

        if ("voluntarios".equalsIgnoreCase(siteSelection)) {
            siteId = SharepointUtil.SP_SITE_ID_VOLUNTARIOS;
        } else if ("informatica".equalsIgnoreCase(siteSelection)) {
            siteId = SharepointUtil.SP_SITE_ID_INFORMATICA;
        }

        try {
            if (listIdOrName != null && !listIdOrName.trim().isEmpty()) {
                // --- OBTENER COLUMNAS E ITEMS DE UNA LISTA ESPECÍFICA ---

                ColumnDefinitionCollectionResponse columnsResponse = SharepointUtil.getListColumns(siteId, listIdOrName);
                List<ColumnDefinition> columns = columnsResponse.getValue();

                List<Map<String, Object>> columnDetails = columns.stream()
                    .filter(c -> c.getHidden() == null || !c.getHidden())
                    .map(c -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("internalName", c.getName());
                        map.put("displayName", c.getDisplayName());
                        map.put("readOnly", c.getReadOnly());
                        if (c.getLookup() != null || c.getName().contains("_x003a_")) {
                            map.put("isLookup", true);
                        }
                        return map;
                    })
                    .collect(Collectors.toList());

                ListItemCollectionResponse itemsResponse = SharepointUtil.getListItems(siteId, listIdOrName);
                List<ListItem> listItems = itemsResponse.getValue();
                
                List<Map<String, Object>> itemDetails = new ArrayList<>();
                if (listItems != null) {
                    for (ListItem item : listItems) {
                         if (item.getFields() != null) {
                            Map<String, Object> processedData = new HashMap<>(item.getFields().getAdditionalData());
                            processedData.put("id", item.getId());
                            itemDetails.add(processedData);
                        }
                    }
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("columns", columnDetails);
                result.put("items", itemDetails);
                objectMapper.writeValue(out, result);

            } else {
                // --- OBTENER TODAS LAS LISTAS DEL SITIO ---
                ListCollectionResponse allLists = SharepointUtil.getAllLists(siteId);
                List<Map<String, String>> listDetails = new ArrayList<>();
                if (allLists != null && allLists.getValue() != null) {
                    for (com.microsoft.graph.models.List list : allLists.getValue()) {
                        Map<String, String> map = new HashMap<>();
                        map.put("id", list.getId());
                        map.put("displayName", list.getDisplayName());
                        map.put("webUrl", list.getWebUrl());
                        listDetails.add(map);
                    }
                }
                objectMapper.writeValue(out, listDetails);
            }

        } catch (Exception e) {
            System.err.println("Error al obtener datos de SharePoint: " + e.getMessage());
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            Map<String, String> errorPayload = new HashMap<>();
            errorPayload.put("error", "Error al procesar la solicitud de SharePoint: " + e.getMessage());
            objectMapper.writeValue(out, errorPayload);
        } finally {
            out.flush();
        }
    }
}