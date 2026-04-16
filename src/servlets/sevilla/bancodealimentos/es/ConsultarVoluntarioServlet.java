package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
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
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet(name = "ConsultarVoluntarioServlet", urlPatterns = {"/consultar-voluntario"})
public class ConsultarVoluntarioServlet extends HttpServlet {

    private static final long serialVersionUID = 3L; // Versión incrementada
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
                data.put("nombre", getString(sharepointData, "Nombre", ""));
                data.put("apellidos", getString(sharepointData, "field_2", ""));
                data.put("email", getString(sharepointData, "field_9", ""));
                data.put("telefono", getString(sharepointData, "field_7", ""));
                data.put("cp", getString(sharepointData, "C.Postal", ""));
                
                // --- PROCESAR FECHA DE NACIMIENTO (field_8) ---
                String fechaNacimiento = "";
                Object fechaObj = sharepointData.get("FechaNacimiento");
                if (fechaObj != null) {
                    String fechaStr = fechaObj.toString().trim();
                    logger.info("Fecha original desde SharePoint: {}", fechaStr);
                    // Registrar en el log de actividad
                    LogUtil.logOperation(con, "SP_FECHA_CAMPO", "Sistema", 
                        "DNI: " + dni + " - Fecha encontrada en campo: field_8 = " + fechaStr);
                    fechaNacimiento = formatearFecha(fechaStr);
                }
                data.put("fechaNacimiento", fechaNacimiento);
                // ---------------------------------------------

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
        return SharePointUtil.findItemByFieldValue(con, SharePointUtil.SP_SITE_ID_INFORMATICA, SharePointUtil.LIST_NAME_VOLUNTARIOS, dniFieldName, dni);
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    /**
     * Convierte varios formatos de fecha a YYYY-MM-DD.
     * Soporta: ISO con hora (YYYY-MM-DDThh:mm:ssZ), DD/MM/YYYY, DD-MM-YYYY.
     */
    private String formatearFecha(String fechaStr) {
        if (fechaStr == null || fechaStr.isEmpty()) return "";
        
        // Si ya viene en formato YYYY-MM-DD, devolver tal cual
        if (fechaStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return fechaStr;
        }
        
        // Si viene en formato ISO con hora (YYYY-MM-DDThh:mm:ssZ), extraer la fecha
        if (fechaStr.matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
            return fechaStr.substring(0, 10);
        }
        
        // Intentar formato DD/MM/YYYY
        try {
            SimpleDateFormat sdfEntrada = new SimpleDateFormat("dd/MM/yyyy");
            Date fecha = sdfEntrada.parse(fechaStr);
            SimpleDateFormat sdfSalida = new SimpleDateFormat("yyyy-MM-dd");
            return sdfSalida.format(fecha);
        } catch (ParseException e) {
            // Ignorar
        }
        
        // Intentar formato DD-MM-YYYY
        try {
            SimpleDateFormat sdfEntrada = new SimpleDateFormat("dd-MM-yyyy");
            Date fecha = sdfEntrada.parse(fechaStr);
            SimpleDateFormat sdfSalida = new SimpleDateFormat("yyyy-MM-dd");
            return sdfSalida.format(fecha);
        } catch (ParseException e) {
            // Ignorar
        }
        
        // Si no se pudo convertir, devolver cadena vacía
        logger.warn("No se pudo formatear la fecha: {}", fechaStr);
        return "";
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("status", "error");
        errorResponse.put("message", message);
        return errorResponse;
    }
}