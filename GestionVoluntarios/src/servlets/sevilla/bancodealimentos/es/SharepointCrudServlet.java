package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.FieldValueSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/crud-sharepoint")
public class SharepointCrudServlet extends HttpServlet {
    private static final long serialVersionUID = 2L; // Versión actualizada
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String siteId = request.getParameter("siteId");
        String listName = request.getParameter("listName");
        String action = request.getParameter("action");
        String itemId = request.getParameter("itemId");
        String payload = request.getParameter("payload");

        if (siteId == null || listName == null || action == null) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "Parámetros 'siteId', 'listName' y 'action' son requeridos."));
            return;
        }

        switch (action.toUpperCase()) {
            case "DELETE":
                handleDelete(siteId, listName, itemId, response);
                break;
            case "CREATE":
                handleCreate(siteId, listName, payload, response);
                break;
            case "UPDATE":
                handleUpdate(siteId, listName, itemId, payload, response);
                break;
            default:
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "Acción no válida. Use CREATE, UPDATE o DELETE."));
                break;
        }
    }

    private void handleDelete(String siteId, String listName, String itemId, HttpServletResponse res) throws IOException {
        if (itemId == null) {
            sendJsonResponse(res, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "El 'itemId' es requerido para la acción DELETE."));
            return;
        }
        try {
            SharepointUtil.deleteListItem(siteId, listName, itemId);
            sendJsonResponse(res, HttpServletResponse.SC_OK, Map.of("success", "Elemento borrado correctamente."));
        } catch (Exception e) {
            handleException(e, res);
        }
    }

    private void handleCreate(String siteId, String listName, String payload, HttpServletResponse res) throws IOException {
        if (payload == null) {
             sendJsonResponse(res, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "El 'payload' (JSON) es requerido para la acción CREATE."));
             return;
        }
        
        try {
            Map<String, Object> fields = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            
            FieldValueSet newFields = new FieldValueSet();
            newFields.setAdditionalData(fields);
            
            SharepointUtil.createListItem(siteId, listName, newFields);

            sendJsonResponse(res, HttpServletResponse.SC_CREATED, Map.of("success", "Elemento creado correctamente."));

        } catch (JsonProcessingException e) {
            sendJsonResponse(res, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "El 'payload' no es un JSON válido: " + e.getMessage()));
        } catch (Exception e) {
            handleException(e, res);
        }
    }

    private void handleUpdate(String siteId, String listName, String itemId, String payload, HttpServletResponse res) throws IOException {
        if (itemId == null || payload == null) {
             sendJsonResponse(res, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "Los parámetros 'itemId' y 'payload' son requeridos para UPDATE."));
             return;
        }
        
         try {
            Map<String, Object> fields = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});

            FieldValueSet fieldsToUpdate = new FieldValueSet();
            fieldsToUpdate.setAdditionalData(fields);

            SharepointUtil.updateListItem(siteId, listName, itemId, fieldsToUpdate);

            sendJsonResponse(res, HttpServletResponse.SC_OK, Map.of("success", "Elemento actualizado correctamente."));

        } catch (JsonProcessingException e) {
            sendJsonResponse(res, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "El 'payload' no es un JSON válido: " + e.getMessage()));
        } catch (Exception e) {
            handleException(e, res);
        }
    }

    private void handleException(Exception e, HttpServletResponse res) throws IOException {
        System.err.println("Error en SharepointCrudServlet: " + e.getMessage());
        e.printStackTrace();
        if (!res.isCommitted()) {
            sendJsonResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of("error", "Error interno en el servidor: " + e.getMessage()));
        }
    }

    private void sendJsonResponse(HttpServletResponse response, int statusCode, Map<String, String> data) throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), data);
    }
}
