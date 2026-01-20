package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.microsoft.graph.models.FieldValueSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/crud-sharepoint")
public class SharepointCrudServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        String siteId = request.getParameter("siteId");
        String listName = request.getParameter("listName");
        String action = request.getParameter("action");
        String itemId = request.getParameter("itemId");
        String payload = request.getParameter("payload");

        if (siteId == null || listName == null || action == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(gson.toJson(Map.of("error", "ParÃ¡metros 'siteId', 'listName' y 'action' son requeridos.")));
            out.flush();
            return;
        }

        switch (action.toUpperCase()) {
            case "DELETE":
                handleDelete(siteId, listName, itemId, response, out);
                break;
            case "CREATE":
                handleCreate(siteId, listName, payload, response, out);
                break;
            case "UPDATE":
                handleUpdate(siteId, listName, itemId, payload, response, out);
                break;
            default:
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.print(gson.toJson(Map.of("error", "AcciÃ³n no vÃ¡lida. Use CREATE, UPDATE o DELETE.")));
                break;
        }
        
        out.flush();
    }

    private void handleDelete(String siteId, String listName, String itemId, HttpServletResponse res, PrintWriter out) {
        if (itemId == null) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(gson.toJson(Map.of("error", "El 'itemId' es requerido para la acciÃ³n DELETE.")));
            return;
        }
        try {
            SharepointUtil.deleteListItem(siteId, listName, itemId);
            res.setStatus(HttpServletResponse.SC_OK);
            out.print(gson.toJson(Map.of("success", "Elemento borrado correctamente.")));
        } catch (Exception e) {
            handleException(e, res, out);
        }
    }

    private void handleCreate(String siteId, String listName, String payload, HttpServletResponse res, PrintWriter out) {
        if (payload == null) {
             res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
             out.print(gson.toJson(Map.of("error", "El 'payload' (JSON) es requerido para la acciÃ³n CREATE.")));
             return;
        }
        
        try {
            Map<String, Object> fields = gson.fromJson(payload, new TypeToken<Map<String, Object>>(){}.getType());
            
            // ** CORRECCIÃ“N: Convertir el Map a FieldValueSet **
            FieldValueSet newFields = new FieldValueSet();
            newFields.setAdditionalData(fields);
            
            // ** CORRECCIÃ“N: Se llama al mÃ©todo con el tipo de dato correcto **
            SharepointUtil.createListItem(siteId, listName, newFields);

            res.setStatus(HttpServletResponse.SC_CREATED);
            out.print(gson.toJson(Map.of("success", "Elemento creado correctamente.")));

        } catch (JsonSyntaxException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(gson.toJson(Map.of("error", "El 'payload' no es un JSON vÃ¡lido: " + e.getMessage())));
        } catch (Exception e) { // ** CORRECCIÃ“N: Captura de excepciÃ³n genÃ©rica **
            handleException(e, res, out);
        }
    }

    private void handleUpdate(String siteId, String listName, String itemId, String payload, HttpServletResponse res, PrintWriter out) {
        if (itemId == null || payload == null) {
             res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
             out.print(gson.toJson(Map.of("error", "Los parÃ¡metros 'itemId' y 'payload' son requeridos para UPDATE.")));
             return;
        }
        
         try {
            Map<String, Object> fields = gson.fromJson(payload, new TypeToken<Map<String, Object>>(){}.getType());

            FieldValueSet fieldsToUpdate = new FieldValueSet();
            fieldsToUpdate.setAdditionalData(fields);

            SharepointUtil.updateListItem(siteId, listName, itemId, fieldsToUpdate);

            res.setStatus(HttpServletResponse.SC_OK);
            out.print(gson.toJson(Map.of("success", "Elemento actualizado correctamente.")));

        } catch (JsonSyntaxException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(gson.toJson(Map.of("error", "El 'payload' no es un JSON vÃ¡lido: " + e.getMessage())));
        } catch (Exception e) { // ** CORRECCIÃ“N: Captura de excepciÃ³n genÃ©rica **
            handleException(e, res, out);
        }
    }

    private void handleException(Exception e, HttpServletResponse res, PrintWriter out) {
        System.err.println("Error en SharepointCrudServlet: " + e.getMessage());
        e.printStackTrace();
        if (!res.isCommitted()) {
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(gson.toJson(Map.of("error", "Error interno en el servidor: " + e.getMessage())));
        }
    }
}

