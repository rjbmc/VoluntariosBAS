package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.ColumnDefinition;
import com.microsoft.graph.models.ColumnDefinitionCollectionResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.SharePointUtil;

/**
 * Servlet de diagnóstico para inspeccionar las columnas de una lista de SharePoint.
 * Su única responsabilidad es obtener y devolver los nombres internos y a mostrar de las columnas.
 */
@WebServlet("/inspeccionar-lista")
public class InspectListServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(InspectListServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String listName = request.getParameter("listName");

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String siteId = SharePointUtil.SITE_ID; // Usamos el SiteID por defecto
        Map<String, Object> jsonResponse = new HashMap<>();

        try {
            if (listName == null || listName.trim().isEmpty()) {
                logger.warn("Petición de inspección sin nombre de lista. IP: {}", request.getRemoteAddr());
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                jsonResponse.put("error", "El parámetro 'listName' es obligatorio.");
                objectMapper.writeValue(response.getWriter(), jsonResponse);
                return;
            }

            logger.info("Inspeccionando estructura de lista SharePoint: {}", listName);

            String listId = SharePointUtil.getListId(siteId, listName);

            if (listId == null) {
                logger.warn("Lista '{}' no encontrada en el sitio configurado.", listName);
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                jsonResponse.put("error", "La lista '" + listName + "' no fue encontrada.");
                objectMapper.writeValue(response.getWriter(), jsonResponse);
                return;
            }

            ColumnDefinitionCollectionResponse columnsResponse = SharePointUtil.getListColumns(siteId, listId);
            List<ColumnDefinition> columns = columnsResponse.getValue();

            // Mapeamos a una lista de objetos simples para el JSON
            List<Map<String, String>> columnDetails = columns.stream()
                .map(c -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("displayName", c.getDisplayName());
                    map.put("internalName", c.getName()); // Este es el valor clave para los 'fields' en Graph API
                    return map;
                })
                .collect(Collectors.toList());

            jsonResponse.put("columns", columnDetails);
            
            // Respuesta exitosa
            objectMapper.writeValue(response.getWriter(), jsonResponse);

        } catch (Exception e) {
            logger.error("Error crítico al inspeccionar la lista '{}'", listName, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("error", "Error interno al inspeccionar la lista: " + e.getMessage());
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }
}