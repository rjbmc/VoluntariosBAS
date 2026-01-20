package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.graph.models.ColumnDefinition;
import com.microsoft.graph.models.ColumnDefinitionCollectionResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.SharepointUtil;

/**
 * Servlet de diagnÃ³stico para inspeccionar las columnas de una lista de SharePoint.
 * Su Ãºnica responsabilidad es obtener y devolver los nombres internos y a mostrar de las columnas.
 */
@WebServlet("/inspeccionar-lista")
public class InspectListServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String listName = request.getParameter("listName");

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        PrintWriter out = response.getWriter();
        
        String siteId = SharepointUtil.SITE_ID; // Usamos el SiteID por defecto

        try {
            if (listName == null || listName.trim().isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.print(gson.toJson(Map.of("error", "El parÃ¡metro 'listName' es obligatorio.")));
                out.flush();
                return;
            }

            String listId = SharepointUtil.getListId(siteId, listName);

            if (listId == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                out.print(gson.toJson(Map.of("error", "La lista '" + listName + "' no fue encontrada.")));
                out.flush();
                return;
            }

            ColumnDefinitionCollectionResponse columnsResponse = SharepointUtil.getListColumns(siteId, listId);
            List<ColumnDefinition> columns = columnsResponse.getValue();

            List<Map<String, String>> columnDetails = columns.stream()
                .map(c -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("displayName", c.getDisplayName());
                    map.put("internalName", c.getName());
                    return map;
                })
                .collect(Collectors.toList());
            
            out.print(gson.toJson(Map.of("columns", columnDetails)));

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(gson.toJson(Map.of("error", "Error interno al inspeccionar la lista: " + e.getMessage())));
        } finally {
            out.flush();
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }
}

