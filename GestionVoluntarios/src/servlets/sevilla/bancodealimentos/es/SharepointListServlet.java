package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
    private static final long serialVersionUID = 1L;

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
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
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

                // PASO 1: OBTENER DEFINICIONES DE COLUMNA (usando el nuevo método)
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

                // PASO 2: OBTENER DATOS DE LA LISTA (usando el nuevo método)
                ListItemCollectionResponse itemsResponse = SharepointUtil.getListItems(siteId, listIdOrName);
                List<ListItem> listItems = itemsResponse.getValue();
                
                List<Map<String, Object>> itemDetails = new ArrayList<>();
                if (listItems != null) {
                    for (ListItem item : listItems) {
                         if (item.getFields() != null) {
                            Map<String, Object> processedData = new HashMap<>(item.getFields().getAdditionalData());
                            processedData.put("id", item.getId()); // Añadir el ID del item
                            itemDetails.add(processedData);
                        }
                    }
                }

                // PASO 3: ENVIAR EL RESULTADO FINAL
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("columns", columnDetails);
                result.put("items", itemDetails);
                out.print(gson.toJson(result));

            } else {
                // --- OBTENER TODAS LAS LISTAS DEL SITIO ---
                // Esta parte es más compleja y por ahora la dejamos así, ya que requiere paginación.
                // Si diera problemas, necesitaríamos un método específico en SharepointUtil para paginar y obtener todas las listas.
                // Por ahora, asumimos que no hay un método público para obtener el GraphClient, así que esta parte fallaría.
                // *** SOLUCIÓN TEMPORAL: Se necesita un método para obtener las listas. ***
                response.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
                out.print(gson.toJson(Map.of("error", "La funcionalidad para listar todas las listas aún no está implementada con la nueva librería.")));
            }

        } catch (Exception e) {
            System.err.println("Error al obtener datos de SharePoint: " + e.getMessage());
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(gson.toJson(Map.of("error", "Error al procesar la solicitud de SharePoint: " + e.getMessage())));
        } finally {
            out.flush();
        }
    }
}
