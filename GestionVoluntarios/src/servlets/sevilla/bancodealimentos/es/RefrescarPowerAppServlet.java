package servlets.sevilla.bancodealimentos.es;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

/**
 * Servlet que gestiona la exportación de datos a Excel para la Power App,
 * envía el archivo por correo y actualiza los registros de cambios.
 */
@WebServlet("/refrescar-powerapp")
public class RefrescarPowerAppServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(RefrescarPowerAppServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Clase interna para mapear asignaciones durante la comparación
    private static class Asignacion {
        String usuario, dni, comentario1, comentario2, comentario3, comentario4;
        int turno1, turno2, turno3, turno4;

        Asignacion(ResultSet rs) throws SQLException {
            this.usuario = rs.getString("Usuario");
            this.dni = rs.getString("DNI NIF");
            this.turno1 = rs.getInt("Turno1");
            this.comentario1 = rs.getString("Comentario1");
            this.turno2 = rs.getInt("Turno2");
            this.comentario2 = rs.getString("Comentario2");
            this.turno3 = rs.getInt("Turno3");
            this.comentario3 = rs.getString("Comentario3");
            this.turno4 = rs.getInt("Turno4");
            this.comentario4 = rs.getString("Comentario4");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);

        // 3. Verificación de seguridad estandarizada
        boolean isAdmin = session != null && 
                          session.getAttribute("usuario") != null && 
                          "S".equals(session.getAttribute("isAdmin"));

        if (!isAdmin) {
            String ip = request.getRemoteAddr();
            logger.warn("Acceso denegado a RefrescarPowerApp. Usuario: {}, IP: {}", 
                        (session != null ? session.getAttribute("usuario") : "Anónimo"), ip);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Acceso denegado.");
            objectMapper.writeValue(response.getWriter(), jsonResponse);
            return;
        }

        String adminUser = (String) session.getAttribute("usuario");
        logger.info("Iniciando proceso de refresco PowerApp solicitado por: {}", adminUser);

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            logger.debug("Generando libro Excel...");
            XSSFWorkbook workbook = new XSSFWorkbook();
            
            // Generación de hojas
            createExcelSheet(conn, workbook, "Voluntarios", "SELECT * FROM voluntarios WHERE notificar = 'S'");
            createExcelSheet(conn, workbook, "Asignaciones", "SELECT * FROM voluntarios_en_campana WHERE notificar = 'S'");
            createResumenAsignacionesSheet(conn, workbook);
            createResumenConCambiosSheet(conn, workbook);

            // Convertir Excel a bytes
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            byte[] excelData = bos.toByteArray();
            workbook.close();
            logger.debug("Excel generado correctamente. Tamaño: {} bytes", excelData.length);

            logger.debug("Enviando correo a Sistemas...");
            sendEmailWithAttachments(excelData);

            logger.debug("Actualizando estados 'notificar' y snapshot...");
            updateNotificarStatus(conn);
            updateSnapshot(conn);
            
            conn.commit();
            
            logger.info("Proceso de refresco PowerApp completado con éxito.");
            LogUtil.logOperation(conn, "EXPORT", adminUser, "Refresco de Power App con análisis de cambios completado.");

            jsonResponse.put("success", true);
            jsonResponse.put("message", "Correo enviado correctamente y registros actualizados.");

        } catch (Exception e) {
            logger.error("Error crítico durante el proceso de refresco PowerApp", e);
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.error("Error al hacer rollback", ex); }
            
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error al procesar la solicitud: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error al cerrar conexión", e); }
        }

        // Respuesta final con Jackson
        objectMapper.writeValue(response.getWriter(), jsonResponse);
    }
    
    // --- Métodos Privados Auxiliares ---

    private void sendEmailWithAttachments(byte[] excelData) throws MessagingException {
        Properties prop = new Properties();
        prop.put("mail.smtp.auth", "true");
        prop.put("mail.smtp.starttls.enable", "true");
        prop.put("mail.smtp.host", Config.SMTP_HOST);
        prop.put("mail.smtp.port", Config.SMTP_PORT);
        
        Session session = Session.getInstance(prop, new jakarta.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(Config.SMTP_USER, Config.SMTP_PASSWORD);
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(Config.SMTP_USER));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(Config.SISTEMAS_EMAIL));
        message.setSubject("Refresco de Datos para Power App - VoluntariosBAS");
        
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Se adjunta el fichero Excel con los últimos cambios y resúmenes registrados en la aplicación de voluntarios.");
        
        MimeBodyPart excelAttachment = new MimeBodyPart();
        DataSource excelDataSource = new ByteArrayDataSource(excelData, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        excelAttachment.setDataHandler(new DataHandler(excelDataSource));
        excelAttachment.setFileName("refresco_powerapp.xlsx");
        
        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(excelAttachment);
        
        message.setContent(multipart);
        Transport.send(message);
        logger.info("Correo enviado exitosamente a {}", Config.SISTEMAS_EMAIL);
    }
    
    private void createExcelSheet(Connection conn, XSSFWorkbook workbook, String sheetName, String query) throws SQLException {
        XSSFSheet sheet = workbook.createSheet(sheetName);
        try (PreparedStatement stmt = conn.prepareStatement(query); ResultSet rs = stmt.executeQuery()) {
            int colCount = rs.getMetaData().getColumnCount();
            Row headerRow = sheet.createRow(0);
            for (int i = 1; i <= colCount; i++) {
                headerRow.createCell(i - 1).setCellValue(rs.getMetaData().getColumnName(i));
            }
            int rowNum = 1;
            while (rs.next()) {
                Row row = sheet.createRow(rowNum++);
                for (int i = 1; i <= colCount; i++) {
                    Object value = rs.getObject(i);
                    row.createCell(i - 1).setCellValue(value != null ? value.toString() : "");
                }
            }
        }
    }
    
    private void createResumenAsignacionesSheet(Connection conn, XSSFWorkbook workbook) throws SQLException {
        XSSFSheet sheet = workbook.createSheet("Resumen_Asignaciones");
        String[] headers = {"CAMPAÑA", "NUM", "USUARIO", "DNI", "T1_TIENDA", "T1_NOTAS", "T2_TIENDA", "T2_NOTAS", "T3_TIENDA", "T3_NOTAS", "T4_TIENDA", "T4_NOTAS", "NTURNOS", "PROCESADO"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            headerRow.createCell(i).setCellValue(headers[i]);
        }
        
        String activeCampaignId = getActiveCampaignId(conn);
        if (activeCampaignId.isEmpty()) return;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        // String timestamp = sdf.format(new Date()); // Opcional, si se usa

        String query = "SELECT vc.Campana, vc.Usuario, v.`DNI NIF`, vc.Turno1, vc.Comentario1, vc.Turno2, vc.Comentario2, vc.Turno3, vc.Comentario3, vc.Turno4, vc.Comentario4 FROM voluntarios_en_campana vc JOIN voluntarios v ON vc.Usuario = v.Usuario WHERE vc.Campana = ? ORDER BY vc.Usuario";
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, activeCampaignId);
            ResultSet rs = stmt.executeQuery();
            int correlativo = 1;
            while (rs.next()) {
                Row row = sheet.createRow(correlativo);
                
                String uniqueNum = sdf.format(new Date()) + "-" + correlativo;

                int nTurnos = 0;
                int turno1 = rs.getInt("Turno1"); if (turno1 > 0) nTurnos++;
                int turno2 = rs.getInt("Turno2"); if (turno2 > 0) nTurnos++;
                int turno3 = rs.getInt("Turno3"); if (turno3 > 0) nTurnos++;
                int turno4 = rs.getInt("Turno4"); if (turno4 > 0) nTurnos++;
                
                row.createCell(0).setCellValue(rs.getString("Campana"));
                row.createCell(1).setCellValue(uniqueNum);
                row.createCell(2).setCellValue(rs.getString("Usuario"));
                row.createCell(3).setCellValue(rs.getString("DNI NIF"));
                row.createCell(4).setCellValue(turno1);
                row.createCell(5).setCellValue(rs.getString("Comentario1"));
                row.createCell(6).setCellValue(turno2);
                row.createCell(7).setCellValue(rs.getString("Comentario2"));
                row.createCell(8).setCellValue(turno3);
                row.createCell(9).setCellValue(rs.getString("Comentario3"));
                row.createCell(10).setCellValue(turno4);
                row.createCell(11).setCellValue(rs.getString("Comentario4"));
                row.createCell(12).setCellValue(nTurnos);
                row.createCell(13).setCellValue("NO");
                
                correlativo++;
            }
        }
    }
    
    private void createResumenConCambiosSheet(Connection conn, XSSFWorkbook workbook) throws SQLException {
        XSSFSheet sheet = workbook.createSheet("Resumen_Con_Cambios");
        
        String[] headers = {"USUARIO", "DNI", "CAMPO_MODIFICADO", "VALOR_ANTERIOR", "VALOR_NUEVO", "ESTADO"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            headerRow.createCell(i).setCellValue(headers[i]);
        }

        String activeCampaignId = getActiveCampaignId(conn);
        if (activeCampaignId.isEmpty()) return;

        Map<String, Asignacion> currentAssignments = getAssignments(conn, "voluntarios_en_campana", activeCampaignId);
        Map<String, Asignacion> snapshotAssignments = getAssignments(conn, "voluntarios_en_campana_snapshot", activeCampaignId);

        Set<String> allUsers = new HashSet<>(currentAssignments.keySet());
        allUsers.addAll(snapshotAssignments.keySet());

        int rowNum = 1;
        for (String user : allUsers) {
            Asignacion current = currentAssignments.get(user);
            Asignacion snapshot = snapshotAssignments.get(user);

            if (current != null && snapshot == null) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(current.usuario);
                row.createCell(1).setCellValue(current.dni);
                row.createCell(5).setCellValue("NUEVA ASIGNACION");
            } else if (current == null && snapshot != null) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(snapshot.usuario);
                row.createCell(1).setCellValue(snapshot.dni);
                row.createCell(5).setCellValue("BAJA DE CAMPAÑA");
            } else if (current != null && snapshot != null) {
                rowNum = compareAssignments(sheet, rowNum, current, snapshot);
            }
        }
    }
    
    private int compareAssignments(XSSFSheet sheet, int rowNum, Asignacion current, Asignacion snapshot) {
        if (current.turno1 != snapshot.turno1) {
            rowNum = createChangeRow(sheet, rowNum, current, "Turno1_Tienda", snapshot.turno1, current.turno1);
        }
        if (!Objects.equals(current.comentario1, snapshot.comentario1)) {
            rowNum = createChangeRow(sheet, rowNum, current, "Turno1_Comentario", snapshot.comentario1, current.comentario1);
        }
        if (current.turno2 != snapshot.turno2) {
            rowNum = createChangeRow(sheet, rowNum, current, "Turno2_Tienda", snapshot.turno2, current.turno2);
        }
        if (!Objects.equals(current.comentario2, snapshot.comentario2)) {
            rowNum = createChangeRow(sheet, rowNum, current, "Turno2_Comentario", snapshot.comentario2, current.comentario2);
        }
        if (current.turno3 != snapshot.turno3) {
            rowNum = createChangeRow(sheet, rowNum, current, "Turno3_Tienda", snapshot.turno3, current.turno3);
        }
        if (!Objects.equals(current.comentario3, snapshot.comentario3)) {
            rowNum = createChangeRow(sheet, rowNum, current, "Turno3_Comentario", snapshot.comentario3, current.comentario3);
        }
        if (current.turno4 != snapshot.turno4) {
            rowNum = createChangeRow(sheet, rowNum, current, "Turno4_Tienda", snapshot.turno4, current.turno4);
        }
        if (!Objects.equals(current.comentario4, snapshot.comentario4)) {
            rowNum = createChangeRow(sheet, rowNum, current, "Turno4_Comentario", snapshot.comentario4, current.comentario4);
        }
        return rowNum;
    }

    private int createChangeRow(XSSFSheet sheet, int rowNum, Asignacion data, String field, Object oldValue, Object newValue) {
        Row row = sheet.createRow(rowNum++);
        row.createCell(0).setCellValue(data.usuario);
        row.createCell(1).setCellValue(data.dni);
        row.createCell(2).setCellValue(field);
        row.createCell(3).setCellValue(oldValue != null ? oldValue.toString() : "N/A");
        row.createCell(4).setCellValue(newValue != null ? newValue.toString() : "N/A");
        row.createCell(5).setCellValue("MODIFICADO");
        return rowNum;
    }
    
    private Map<String, Asignacion> getAssignments(Connection conn, String tableName, String campaignId) throws SQLException {
        Map<String, Asignacion> map = new HashMap<>();
        String query = "SELECT vc.*, v.`DNI NIF` " +
                       "FROM " + tableName + " vc " +
                       "JOIN voluntarios v ON vc.Usuario = v.Usuario " +
                       "WHERE vc.Campana = ?";
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, campaignId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Asignacion a = new Asignacion(rs);
                map.put(a.usuario, a);
            }
        }
        return map;
    }

    private void updateSnapshot(Connection conn) throws SQLException {
        String activeCampaignId = getActiveCampaignId(conn);
        if (activeCampaignId.isEmpty()) return;
        
        String deleteSql = "DELETE FROM voluntarios_en_campana_snapshot WHERE Campana = ?";
        try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
            stmt.setString(1, activeCampaignId);
            stmt.executeUpdate();
        }

        String insertSql = "INSERT INTO voluntarios_en_campana_snapshot SELECT * FROM voluntarios_en_campana WHERE Campana = ?";
        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            stmt.setString(1, activeCampaignId);
            stmt.executeUpdate();
        }
    }

    private String getActiveCampaignId(Connection conn) throws SQLException {
        String activeCampaignQuery = "SELECT Campana FROM campanas WHERE estado = 'S' LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(activeCampaignQuery);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getString("Campana");
            }
        }
        return "";
    }
    
    private void updateNotificarStatus(Connection conn) throws SQLException {
        try (PreparedStatement stmt1 = conn.prepareStatement("UPDATE voluntarios SET notificar = 'N' WHERE notificar = 'S'");
             PreparedStatement stmt2 = conn.prepareStatement("UPDATE voluntarios_en_campana SET notificar = 'N' WHERE notificar = 'S'");
             PreparedStatement stmt3 = conn.prepareStatement("UPDATE tiendas SET notificar = 'N' WHERE notificar = 'S'");
             PreparedStatement stmt4 = conn.prepareStatement("UPDATE campanas SET notificar = 'N' WHERE notificar = 'S'")) {
            stmt1.executeUpdate();
            stmt2.executeUpdate();
            stmt3.executeUpdate();
            stmt4.executeUpdate();
        }
    }
}