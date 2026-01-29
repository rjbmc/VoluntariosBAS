package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet(name = "ConsultarVoluntarioServlet", urlPatterns = {"/consultar-voluntario"})
public class ConsultarVoluntarioServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String VOLUNTARIOS_LIST_NAME = "Voluntarios";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(ConsultarVoluntarioServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String dni = request.getParameter("dni");

        if (dni == null || dni.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            mapper.writeValue(response.getWriter(), createErrorResponse("El DNI es obligatorio."));
            return;
        }

        try (Connection con = DatabaseUtil.getConnection()) {
            // 1. Buscar en la base de datos local
            String sql = "SELECT COUNT(*) FROM voluntarios WHERE `DNI NIF` = ?";
            try (PreparedStatement pst = con.prepareStatement(sql)) {
                pst.setString(1, dni);
                try (ResultSet rs = pst.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        response.setStatus(HttpServletResponse.SC_CONFLICT);
                        mapper.writeValue(response.getWriter(), createErrorResponse("Este DNI ya está registrado en la aplicación."));
                        return;
                    }
                }
            }
            
            // 2. Si no está en la BBDD, buscar en SharePoint
            Map<String, Object> sharepointData = findVoluntarioInSharePoint(con, dni);

            if (sharepointData != null) {
                // 3. Encontrado en SharePoint: devolver datos para pre-rellenar
                Map<String, Object> data = new HashMap<>();
                data.put("nombre", sharepointData.getOrDefault("field_1", ""));
                data.put("apellidos", sharepointData.getOrDefault("field_2", ""));
                data.put("email", sharepointData.getOrDefault("field_6", ""));
                data.put("telefono", sharepointData.getOrDefault("field_7", ""));
                data.put("cp", sharepointData.getOrDefault("field_9", ""));

                Map<String, Object> jsonResponse = new HashMap<>();
                jsonResponse.put("status", "success");
                jsonResponse.put("source", "sharepoint");
                jsonResponse.put("data", data);
                mapper.writeValue(response.getWriter(), jsonResponse);
            } else {
                // 4. No encontrado en ningún sitio
                Map<String, Object> jsonResponse = new HashMap<>();
                jsonResponse.put("status", "success");
                jsonResponse.put("source", "none");
                mapper.writeValue(response.getWriter(), jsonResponse);
            }

        } catch (SQLException e) {
            logger.error("Error de SQL en ConsultarVoluntarioServlet", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            mapper.writeValue(response.getWriter(), createErrorResponse("Error al conectar con la base de datos."));
        } catch (Throwable t) {
            logger.error("Error FATAL en ConsultarVoluntarioServlet", t);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            mapper.writeValue(response.getWriter(), createErrorResponse("Error grave al procesar la solicitud. Ver logs."));
        }
    }

    private Map<String, Object> findVoluntarioInSharePoint(Connection con, String dni) throws Exception {
        final String dniFieldName = "field_3";
        return SharePointUtil.findItemByFieldValue(con, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, VOLUNTARIOS_LIST_NAME, dniFieldName, dni);
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("status", "error");
        errorResponse.put("message", message);
        return errorResponse;
    }
}
